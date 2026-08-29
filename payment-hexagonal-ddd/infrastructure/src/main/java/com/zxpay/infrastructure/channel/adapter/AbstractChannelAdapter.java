package com.zxpay.infrastructure.channel.adapter;

import com.zxpay.domain.channel.model.ChannelCode;
import com.zxpay.domain.channel.model.InteractionMode;
import com.zxpay.domain.payment.model.Authorization;
import com.zxpay.domain.payment.model.ChannelInteraction;
import com.zxpay.domain.payment.model.ChannelRawStatus;
import com.zxpay.domain.payment.model.ChannelRequest;
import com.zxpay.domain.payment.model.ChannelResult;
import com.zxpay.domain.payment.model.FailureInfo;
import com.zxpay.domain.payment.model.PaymentStatus;
import com.zxpay.domain.payment.port.ChannelPaymentPort;
import com.zxpay.domain.payment.port.ChannelQueryPort;
import com.zxpay.sharedkernel.time.ClockHolder;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * 通道适配器基类：模板方法模式。
 *
 * <p>把「下单 → 拿结果 → 翻译成归一化结果」这条骨架固定在基类里，
 * 各通道子类只声明自己的真实差异：状态名、唤起参数形态、错误码。
 * 这样 9 家通道的适配器绝大部分是配置而非代码。
 *
 * <h3>这是教学 Demo，通道调用是模拟的</h3>
 * <p>真实实现要做的事在 {@link #invokeChannel} 的注释里逐条列出。
 * 模拟策略刻意做成<b>确定性</b>的：同一个 attemptId 永远得到同一个结果。
 * 这不是偷懒——确定性让「重试」这件事可以被真正验证：
 * 重试时 attemptId 不变，因此结果不变，不会因为随机性掩盖幂等缺陷。
 *
 * <p>模拟覆盖五种真实存在的返回：
 * <ol>
 *   <li>同步成功（用户已付款，通道直接返回终态）</li>
 *   <li>待支付（需要前端唤起收银台）</li>
 *   <li>用户支付中（微信付款码的典型中间态）</li>
 *   <li>业务失败（余额不足、风控拦截——重试无意义）</li>
 *   <li><b>结果未知（超时）</b>——最危险的一类，必须转成 UNKNOWN 而不是 FAILED</li>
 * </ol>
 */
public abstract class AbstractChannelAdapter implements ChannelPaymentPort, ChannelQueryPort {

    /** 授权有效期。卡组通常 7 天。 */
    protected static final Duration AUTHORIZATION_TTL = Duration.ofDays(7);

    @Override
    public abstract ChannelCode channel();

    // =====================================================================
    // 子类需要声明的差异
    // =====================================================================

    /** 待支付状态在通道侧的字符串。 */
    protected abstract String pendingRawStatus();

    /** 成功状态字符串。 */
    protected abstract String successRawStatus();

    /** 失败状态字符串。 */
    protected abstract String failureRawStatus();

    /** 用户支付中状态字符串。不支持该中间态的通道返回 null。 */
    protected String userPayingRawStatus() {
        return null;
    }

    /** 已授权状态字符串。不支持授权分离的通道返回 null。 */
    protected String authorizedRawStatus() {
        return null;
    }

    /** 通道专属错误码前缀，便于日志识别来源。 */
    protected String errorCodePrefix() {
        return channel().name();
    }

    // =====================================================================
    // 下单
    // =====================================================================

    @Override
    public ChannelResult pay(ChannelRequest request) {
        Instant respondedAt = ClockHolder.now();
        SimulatedOutcome outcome = simulate(request);

        // 关键：幂等键必须来自领域层传入的值，适配器绝不能自己生成
        String idempotencyKey = request.idempotencyKey();

        return switch (outcome) {
            case SUCCESS -> ChannelResult.succeeded(
                    channel(), request.attemptId(), idempotencyKey,
                    generateTransactionId(request),
                    request.channelOrderNo(),
                    rawStatus(successRawStatus(), PaymentStatus.SUCCEEDED, "支付成功"),
                    request.amount(), respondedAt, respondedAt);

            case USER_PAYING -> ChannelResult.pending(
                    channel(), request.attemptId(), idempotencyKey, request.channelOrderNo(),
                    rawStatus(userPayingRawStatus(), PaymentStatus.USERPAYING, "用户支付中，等待确认"),
                    ChannelInteraction.none(), respondedAt);

            case AUTHORIZED -> {
                Authorization authorization = new Authorization(
                        generateAuthorizationId(request),
                        request.amount(),
                        respondedAt,
                        respondedAt.plus(AUTHORIZATION_TTL),
                        null);
                yield ChannelResult.authorized(
                        channel(), request.attemptId(), idempotencyKey, request.channelOrderNo(),
                        rawStatus(authorizedRawStatus(), PaymentStatus.AUTHORIZED, "已授权，待请款"),
                        authorization, respondedAt);
            }

            case FAILURE_BUSINESS -> ChannelResult.failed(
                    channel(), request.attemptId(), idempotencyKey, request.channelOrderNo(),
                    rawStatus(failureRawStatus(), PaymentStatus.FAILED, "支付失败"),
                    FailureInfo.business(errorCodePrefix() + "_INSUFFICIENT_BALANCE", "余额不足"),
                    respondedAt);

            case FAILURE_RISK -> ChannelResult.failed(
                    channel(), request.attemptId(), idempotencyKey, request.channelOrderNo(),
                    rawStatus(failureRawStatus(), PaymentStatus.FAILED, "风控拦截"),
                    FailureInfo.risk(errorCodePrefix() + "_RISK_REJECTED", "交易被风控拦截"),
                    respondedAt);

            // 超时：结果未知。绝不判失败，交给上层查单
            case TIMEOUT -> ChannelResult.failed(
                    channel(), request.attemptId(), idempotencyKey, request.channelOrderNo(),
                    rawStatus("TIMEOUT", PaymentStatus.PAYING, "请求超时，结果未知"),
                    FailureInfo.unknown(errorCodePrefix() + "_TIMEOUT", "调用通道超时，结果未知，必须查单确认"),
                    respondedAt);

            case PENDING -> ChannelResult.pending(
                    channel(), request.attemptId(), idempotencyKey, request.channelOrderNo(),
                    rawStatus(pendingRawStatus(), PaymentStatus.PAYING, "等待用户支付"),
                    buildInteraction(request), respondedAt);
        };
    }

    // =====================================================================
    // 查单
    // =====================================================================

    @Override
    public ChannelResult query(ChannelQueryPort.ChannelQueryRequest request) {
        // 真实实现：调用通道的订单查询接口，把响应翻译成 ChannelResult。
        // 这里复用与下单相同的确定性模拟，保证「下单超时 → 查单」能拿到一致结论。
        Instant respondedAt = ClockHolder.now();
        SimulatedOutcome outcome = simulateById(request.attemptId());

        ChannelRawStatus raw = switch (outcome) {
            case SUCCESS -> rawStatus(successRawStatus(), PaymentStatus.SUCCEEDED, "查单确认支付成功");
            case USER_PAYING -> rawStatus(userPayingRawStatus(), PaymentStatus.USERPAYING, "用户仍在支付中");
            case AUTHORIZED -> rawStatus(authorizedRawStatus(), PaymentStatus.AUTHORIZED, "已授权待请款");
            case TIMEOUT -> rawStatus("TIMEOUT", PaymentStatus.PAYING, "查单超时，仍未知");
            default -> rawStatus(pendingRawStatus(), PaymentStatus.PAYING, "等待用户支付");
        };

        String idempotencyKey = "query:" + request.attemptId().value();

        return switch (outcome) {
            case SUCCESS -> ChannelResult.succeeded(
                    channel(), request.attemptId(), idempotencyKey,
                    generateTransactionId(null), request.merchantOrderNo(), raw,
                    null, respondedAt, respondedAt);
            case TIMEOUT -> ChannelResult.failed(
                    channel(), request.attemptId(), idempotencyKey, request.merchantOrderNo(), raw,
                    FailureInfo.unknown(errorCodePrefix() + "_QUERY_TIMEOUT", "查单超时"), respondedAt);
            default -> ChannelResult.pending(
                    channel(), request.attemptId(), idempotencyKey,
                    request.merchantOrderNo(), raw, ChannelInteraction.none(), respondedAt);
        };
    }

    // =====================================================================
    // 唤起参数：按交互形态翻译，消灭上层的 if-else
    // =====================================================================

    /**
     * 按请求的交互形态构造前端唤起参数。
     *
     * <p>各通道的差异全在这一处收口：微信 NATIVE 返回 code_url、
     * JSAPI 返回 prepay 参数、Stripe 返回 client_secret、PayPal 返回 approve_url。
     * 上层拿到的永远是同一个 {@link ChannelInteraction}，不需要知道是谁家的。
     */
    protected ChannelInteraction buildInteraction(ChannelRequest request) {
        InteractionMode mode = request.interactionMode();
        String orderNo = request.channelOrderNo();

        return switch (mode) {
            case SCAN_QR -> ChannelInteraction.qrCode(codeUrlOf(orderNo));
            case REDIRECT -> ChannelInteraction.redirect(checkoutUrlOf(orderNo));
            case FRONTEND_SDK -> ChannelInteraction.sdk(mode, sdkParamsOf(request));
            case BARCODE -> ChannelInteraction.none();   // 条码支付无前端动作，后台直扣
            case ASYNC_INSTRUCTION -> ChannelInteraction.instruction(offlineInstructionOf(request));
            case API_ONLY -> ChannelInteraction.none();
        };
    }

    /** 二维码内容。各通道协议不同。 */
    protected abstract String codeUrlOf(String channelOrderNo);

    /** 收银台跳转 URL。 */
    protected abstract String checkoutUrlOf(String channelOrderNo);

    /** SDK 唤起参数。 */
    protected abstract Map<String, String> sdkParamsOf(ChannelRequest request);

    /** 线下转账的收款信息文案。 */
    protected String offlineInstructionOf(ChannelRequest request) {
        return "请按商户提供的收款信息完成转账，并备注订单号 " + request.channelOrderNo();
    }

    // =====================================================================
    // 模拟实现（教学用，真实实现见下方注释）
    // =====================================================================

    /**
     * 模拟通道调用。
     *
     * <p><b>真实实现要做的事：</b>
     * <ol>
     *   <li>按通道规范拼接签名串（微信 APIv3 是 方法\nURL\n时间戳\n随机串\n报文体\n
     *       并用商户证书私钥签名；支付宝是 RSA2 对排序后的参数签名）。</li>
     *   <li>把 {@link ChannelRequest} 翻译成通道专属报文
     *       （微信的 {@code out_trade_no / amount.total}、Stripe 的 {@code amount / currency}）。</li>
     *   <li>发 HTTPS 请求，设置连接与读取超时（建议 3s / 10s）。</li>
     *   <li>处理响应：验签、解密敏感字段（微信 APIv3 的 AEAD 解密）、映射状态码。</li>
     *   <li><b>超时与网络异常一律转成 {@code FailureInfo.unknown}</b>，绝不返回失败。</li>
     * </ol>
     */
    protected SimulatedOutcome simulate(ChannelRequest request) {
        return simulateById(request.attemptId());
    }

    /**
     * 按 attemptId 做确定性模拟。
     *
     * <p>分布刻意偏向成功，但保证五种结果都会出现，
     * 便于在演示中观察每条分支。
     */
    protected SimulatedOutcome simulateById(com.zxpay.domain.payment.model.PaymentAttemptId attemptId) {
        int hash = Math.abs(Objects.requireNonNull(attemptId).value().hashCode());
        int bucket = hash % 100;

        if (bucket < 55) {
            // 大多数场景：需要用户在前端完成支付
            return userPayingRawStatus() != null && bucket < 8
                    ? SimulatedOutcome.USER_PAYING
                    : SimulatedOutcome.PENDING;
        }
        if (bucket < 68) {
            return SimulatedOutcome.SUCCESS;
        }
        if (bucket < 76) {
            return authorizedRawStatus() != null ? SimulatedOutcome.AUTHORIZED : SimulatedOutcome.PENDING;
        }
        if (bucket < 84) {
            return SimulatedOutcome.FAILURE_BUSINESS;
        }
        if (bucket < 88) {
            return SimulatedOutcome.FAILURE_RISK;
        }
        // 12% 超时——真实系统里这个比例足以逼出「结果未知」这条链路的重要性
        return SimulatedOutcome.TIMEOUT;
    }

    protected ChannelRawStatus rawStatus(String raw, PaymentStatus normalized, String description) {
        return ChannelRawStatus.of(raw, normalized, description, ClockHolder.now());
    }

    protected String generateTransactionId(ChannelRequest request) {
        return channel().name() + "TXN" + Math.abs(Objects.hash(channel(), request == null ? "q" : request.attemptId().value())) % 900000000L;
    }

    protected String generateAuthorizationId(ChannelRequest request) {
        return channel().name() + "AUTH" + Math.abs(Objects.hash(channel(), request.attemptId().value())) % 900000000L;
    }

    /** 模拟结果类型。 */
    protected enum SimulatedOutcome {
        PENDING, USER_PAYING, SUCCESS, AUTHORIZED, FAILURE_BUSINESS, FAILURE_RISK, TIMEOUT
    }
}
