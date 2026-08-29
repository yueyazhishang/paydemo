package com.zxpay.infrastructure.channel.notify;

import com.zxpay.domain.channel.model.ChannelCode;
import com.zxpay.domain.notify.model.NotificationEnvelope;
import com.zxpay.domain.notify.model.NotificationPayload;
import com.zxpay.domain.notify.port.ChannelNotifyParser;
import com.zxpay.domain.notify.port.ChannelNotifyVerifier;
import com.zxpay.domain.payment.model.PaymentStatus;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * 通用回调处理器：验签 + 解析。
 *
 * <p>九家通道的回调处理，骨架完全一致，差异只在两处：
 * <ol>
 *   <li><b>验签算法</b>：微信平台证书 / 支付宝 RSA2 / Stripe HMAC / PayPal 反向调用。</li>
 *   <li><b>状态映射表</b>：各家的状态字符串不同。</li>
 * </ol>
 *
 * <p>因此用一个通用处理器 + 每家一张映射表，比为每家写一个类更清晰——
 * 后者会产生九个 90% 雷同的类，改一处流程要改九遍。
 *
 * <h3>顺序不能颠倒：先验签，后解析</h3>
 * <p>任何「先解析再验签」的实现都有被伪造报文攻击的风险。
 * 回调地址是公网可访问的，攻击者只要知道订单号就能构造
 * 「支付成功」请求打过来；不验签等于把「标记订单已支付」的权限开放给公网。
 */
public class DefaultChannelNotifyHandler implements ChannelNotifyVerifier, ChannelNotifyParser {

    /** 验签时间戳的容忍窗口。超出则判定为重放攻击。 */
    private static final Duration REPLAY_TOLERANCE = Duration.ofMinutes(5);

    private final ChannelCode channel;

    /** 通道原始状态 → 归一化状态。 */
    private final Map<String, PaymentStatus> statusMapping;

    public DefaultChannelNotifyHandler(ChannelCode channel, Map<String, PaymentStatus> statusMapping) {
        this.channel = channel;
        this.statusMapping = statusMapping;
    }

    public ChannelCode channel() {
        return channel;
    }

    // =====================================================================
    // 验签
    // =====================================================================

    @Override
    public VerifyOutcome verify(NotificationEnvelope envelope) {
        if (envelope.rawBody() == null || envelope.rawBody().isBlank()) {
            return VerifyOutcome.invalid("empty notification body");
        }

        String timestamp = envelope.header("timestamp");
        if (timestamp != null) {
            long ts;
            try {
                ts = Long.parseLong(timestamp);
            } catch (NumberFormatException e) {
                return VerifyOutcome.invalid("malformed timestamp: " + timestamp);
            }
            long nowSeconds = System.currentTimeMillis() / 1000;
            // 防重放：即便签名正确，时间戳超出容忍窗口也拒绝。
            // 少了这一步，攻击者录下一次合法请求就能无限重放。
            if (Math.abs(nowSeconds - ts) > REPLAY_TOLERANCE.toSeconds()) {
                return VerifyOutcome.replaySuspected("timestamp out of tolerance window: " + timestamp);
            }
        }

        String signature = envelope.header(signatureHeaderName());
        if (signature == null || signature.isBlank()) {
            return VerifyOutcome.invalid("missing signature header: " + signatureHeaderName());
        }

        // 真实实现按通道分支：
        //   微信 APIv3：Wechatpay-Signature + Wechatpay-Serial，用平台证书验 RSA-SHA256，
        //               且平台证书需定期轮换（不能硬编码一张）。
        //   支付宝：报文内的 sign 字段 + sign_type=RSA2，用支付宝公钥验。
        //   Stripe：Stripe-Signature 头，HMAC-SHA256(webhook_secret, timestamp + "." + body)。
        //   PayPal：不能本地验，必须回调 /v1/notifications/verify-webhook-signature 反向确认。
        // 本 Demo 一律视为通过，仅做结构校验。
        return VerifyOutcome.verified();
    }

    // =====================================================================
    // 解析
    // =====================================================================

    @Override
    public Optional<NotificationPayload> parse(NotificationEnvelope envelope) {
        Map<String, String> fields = parseFields(envelope.rawBody());

        String rawStatus = fields.get("trade_status");
        if (rawStatus == null) {
            rawStatus = fields.get("status");
        }
        if (rawStatus == null) {
            rawStatus = fields.get("respCode");
        }
        if (rawStatus == null || rawStatus.isBlank()) {
            return Optional.empty();
        }

        PaymentStatus normalized = statusMapping.get(rawStatus);
        if (normalized == null) {
            // 未知状态：返回空让上层记录告警，而不是猜一个状态。
            // 通道新增状态时，宁可先告警也不要静默映射错。
            return Optional.empty();
        }

        return Optional.of(new NotificationPayload(
                channel,
                fields.get("out_trade_no") != null ? fields.get("out_trade_no") : fields.get("order_no"),
                fields.get("transaction_id") != null ? fields.get("transaction_id") : fields.get("order_id"),
                rawStatus,
                normalized,
                null,
                null,
                null,
                fields));
    }

    /** 各家报文里的签名头名称。 */
    private String signatureHeaderName() {
        return switch (channel) {
            case WECHAT_PAY -> "Wechatpay-Signature";
            case STRIPE -> "Stripe-Signature";
            case PAYPAL -> "Paypal-Transmission-Sig";
            case WORLDPAY -> "X-Wp-Signature";
            default -> "X-Signature";
        };
    }

    /** 极简的 key=value& 或 JSON 字段提取，仅供教学演示。 */
    private Map<String, String> parseFields(String rawBody) {
        Map<String, String> fields = new java.util.LinkedHashMap<>();
        String body = rawBody.trim();

        if (body.startsWith("{")) {
            // 粗略的 JSON 解析：真实实现用 Jackson，且必须保留原始 body 用于验签
            String stripped = body.replace("{", "").replace("}", "").replace("\"", "");
            for (String pair : stripped.split(",")) {
                String[] kv = pair.split(":", 2);
                if (kv.length == 2) {
                    fields.put(kv[0].trim(), kv[1].trim());
                }
            }
        } else {
            for (String pair : body.split("&")) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2) {
                    fields.put(kv[0].trim(), kv[1].trim());
                }
            }
        }
        return fields;
    }

    // =====================================================================
    // 各通道状态映射表
    // =====================================================================

    /**
     * 建立某通道的状态映射表。
     *
     * <p>这里集中体现了「同一件事，各家说不同的话」：
     * 支付宝的 {@code TRADE_SUCCESS} 与 {@code TRADE_FINISHED} 都归一化为 SUCCEEDED，
     * 但前者可退款、后者不可——这正是归一化状态必须双轨保留原始值的原因。
     */
    public static DefaultChannelNotifyHandler of(ChannelCode channel) {
        Map<String, PaymentStatus> mapping = switch (channel) {
            case WECHAT_PAY -> Map.of(
                    "NOTPAY", PaymentStatus.PAYING,
                    "USERPAYING", PaymentStatus.USERPAYING,
                    "SUCCESS", PaymentStatus.SUCCEEDED,
                    "CLOSED", PaymentStatus.CLOSED,
                    "REVOKED", PaymentStatus.CLOSED,
                    "PAYERROR", PaymentStatus.FAILED);
            case ALIPAY -> Map.of(
                    "WAIT_BUYER_PAY", PaymentStatus.PAYING,
                    "TRADE_SUCCESS", PaymentStatus.SUCCEEDED,
                    "TRADE_FINISHED", PaymentStatus.SUCCEEDED,
                    "TRADE_CLOSED", PaymentStatus.CLOSED);
            case JD_PAY -> Map.of(
                    "WAIT", PaymentStatus.PAYING,
                    "SUCCESS", PaymentStatus.SUCCEEDED,
                    "FAILED", PaymentStatus.FAILED,
                    "CLOSED", PaymentStatus.CLOSED);
            case UNIONPAY -> Map.of(
                    "00", PaymentStatus.SUCCEEDED,
                    "01", PaymentStatus.FAILED,
                    "02", PaymentStatus.PAYING,
                    "03", PaymentStatus.CLOSED);
            case STRIPE -> Map.of(
                    "requires_payment_method", PaymentStatus.PAYING,
                    "requires_confirmation", PaymentStatus.PAYING,
                    "requires_action", PaymentStatus.PAYING,
                    "processing", PaymentStatus.PAYING,
                    "requires_capture", PaymentStatus.AUTHORIZED,
                    "succeeded", PaymentStatus.SUCCEEDED,
                    "canceled", PaymentStatus.CLOSED);
            case PAYPAL -> Map.of(
                    "CREATED", PaymentStatus.PAYING,
                    "PAYER_ACTION_REQUIRED", PaymentStatus.PAYING,
                    "APPROVED", PaymentStatus.AUTHORIZED,
                    "COMPLETED", PaymentStatus.SUCCEEDED,
                    "VOIDED", PaymentStatus.CLOSED);
            case ANTOM -> Map.of(
                    "INIT", PaymentStatus.PAYING,
                    "PROCESSING", PaymentStatus.PAYING,
                    "AUTHORIZED", PaymentStatus.AUTHORIZED,
                    "SUCCESS", PaymentStatus.SUCCEEDED,
                    "FAIL", PaymentStatus.FAILED,
                    "CLOSED", PaymentStatus.CLOSED);
            case WORLDPAY -> Map.of(
                    "PENDING", PaymentStatus.PAYING,
                    "AUTHORIZED", PaymentStatus.AUTHORIZED,
                    "CAPTURED", PaymentStatus.SUCCEEDED,
                    "SETTLED", PaymentStatus.SUCCEEDED,
                    "REFUSED", PaymentStatus.FAILED,
                    "CANCELLED", PaymentStatus.CLOSED);
            case APPLE_PAY -> Map.of();
        };
        return new DefaultChannelNotifyHandler(channel, mapping);
    }
}
