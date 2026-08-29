package com.demo.payment.adapter.stripe;

import com.demo.payment.adapter.core.AbstractChannelAdapter;
import com.demo.payment.domain.acquiring.model.vo.OutTradeNo;
import com.demo.payment.domain.channel.model.*;
import com.demo.payment.domain.channel.spi.*;
import com.demo.payment.shared.money.Currency;
import com.demo.payment.shared.money.Money;

import java.time.Instant;
import java.util.Set;

/**
 * Stripe 适配器（PaymentIntent）。
 *
 * <h3>为什么 Stripe 的设计被称为行业标杆</h3>
 * <p>PaymentIntent 把一笔支付建模成一个<b>显式状态机</b>：
 * <pre>
 *   requires_payment_method → requires_confirmation → requires_action(3DS)
 *        → processing → succeeded
 *                     ↘ requires_capture（手动请款模式）
 *                     ↘ canceled / payment_failed
 * </pre>
 *
 * <p>这个设计的价值在于：<b>它承认"支付是一个过程，而不是一次调用"</b>。
 * 对比早期支付 API 的 charge 模式（一次调用要么成功要么失败），
 * PaymentIntent 能表达"需要 3DS 验证"、"需要手动请款"这些中间态，
 * 从而支持 SCA（Strong Customer Authentication）等合规要求。
 *
 * <h3>三个工程亮点</h3>
 * <ol>
 *   <li><b>Idempotency-Key 请求头</b>：24 小时内同键返回首次结果。
 *       这让"重试"变得安全 —— 网络超时后可以直接重发，
 *       不必先查单。这是 Stripe 相对国内通道的巨大工程优势。</li>
 *   <li><b>Webhook 带时间戳防重放</b>：签名串包含 timestamp，
 *       超过容忍窗口（默认 5 分钟）的请求直接拒绝，防止重放攻击。
 *       国内通道的回调大多没有这个机制。</li>
 *   <li><b>金额一律用最小单位整数</b>：Stripe 用 cents（JPY 例外，用整数日元）。
 *       与 Money 的设计天然吻合。</li>
 * </ol>
 */
public class StripeAdapter extends AbstractChannelAdapter {

    private static final ChannelCapability CAPABILITY = new ChannelCapability(
            ChannelCode.STRIPE,
            "Stripe",
            ChannelCapability.AcquiringModel.CARD_ACQUIRING,
            Set.of(PaymentMethodType.BANK_CARD, PaymentMethodType.APPLE_PAY, PaymentMethodType.GOOGLE_PAY),
            true,
            true,
            true,
            true,
            180,
            true,
            ChannelCapability.NotifyMode.PUSH_AND_PULL,
            ChannelCapability.IdempotencyMode.HEADER_IDEMPOTENCY_KEY,
            ChannelCapability.SignatureAlgorithm.STRIPE_WEBHOOK_HMAC,
            false,
            Set.of(ChannelCapability.IntegrationMode.EMBEDDED_ELEMENT, ChannelCapability.IntegrationMode.API_ONLY, ChannelCapability.IntegrationMode.REDIRECT_CHECKOUT),
            Set.of(Currency.USD, Currency.EUR, Currency.GBP, Currency.JPY, Currency.AUD, Currency.SGD, Currency.HKD),
            50L,
            99999999L,
            java.time.Duration.ofMinutes(1440),
            true,
            ChannelCapability.SettlementMode.DEFERRED
    );

    @Override
    public ChannelCode channelCode() {
        return ChannelCode.STRIPE;
    }

    @Override
    public ChannelCapability capability() {
        return CAPABILITY;
    }

    /**
     * 创建 PaymentIntent。
     *
     * <p><b>幂等实现</b>：把 {@code idempotencyKey} 放进 {@code Idempotency-Key} 请求头。
     * 这是 Stripe 最值得学习的一点 —— 幂等由通道侧保证，
     * 重试时无需先查单，大幅简化客户端逻辑。
     */
    @Override
    protected PayResponse doPay(PayCommand command) {
        String captureMethod = command.extraParams().getOrDefault("captureMethod", "automatic");

        // TODO 真实实现：POST /v1/payment_intents
        //   请求头：Idempotency-Key: {idempotencyKey}
        //   body: amount={最小单位整数}&currency=usd&payment_method={pm_id}
        //        &capture_method=automatic|manual&confirmation_method=automatic
        //        &confirm=true

        String piId = "pi_" + System.currentTimeMillis();

        return PayResponse.pending(command.outTradeNo(), cred(
                "paymentIntentId", piId,
                "clientSecret", piId + "_secret_mock",
                "captureMethod", captureMethod,
                "status", "requires_confirmation"
        ));
    }

    @Override
    protected QueryResponse doQuery(QueryCommand command) {
        // TODO 真实实现：GET /v1/payment_intents/{id}
        return QueryResponse.unknown(command.outTradeNo(), "Mock: 未实现真实查证");
    }

    @Override
    protected CloseResponse doClose(CloseCommand command) {
        // TODO 真实实现：POST /v1/payment_intents/{id}/cancel
        return CloseResponse.success(command.outTradeNo());
    }

    /** 撤销授权：manual capture 模式下释放冻结额度 */
    @Override
    protected CancelResponse doCancel(CancelCommand command) {
        // TODO 真实实现：POST /v1/payment_intents/{id}/cancel
        return CancelResponse.success(command.outTradeNo());
    }

    @Override
    protected CaptureResponse doCapture(CaptureCommand command) {
        // TODO 真实实现：POST /v1/payment_intents/{id}/capture
        //   支持 amount_to_capture 小于授权额，差额自动释放
        return CaptureResponse.succeeded(command.outTradeNo().value(), "STRCAP" + System.currentTimeMillis(),
                command.amount());
    }

    @Override
    protected RefundResponse doRefund(RefundCommand command) {
        // TODO 真实实现：POST /v1/refunds
        //   请求头带 Idempotency-Key；body: payment_intent={pi_id}&amount={最小单位}
        return RefundResponse.succeeded(command.outRefundNo(), "re_" + System.currentTimeMillis(),
                command.amount());
    }

    /**
     * 回调解析。
     *
     * <p><b>Stripe 签名格式：</b>{@code Stripe-Signature: t=1614556800,v1=5257a869e7...}
     * 验签步骤：
     * <ol>
     *   <li>取 t（时间戳），检查是否在容忍窗口内（防重放）</li>
     *   <li>拼接 {@code "t" + "." + body}，用 webhook secret 算 HMAC-SHA256</li>
     *   <li>与 v1 值<b>常量时间比较</b>（防时序攻击）</li>
     * </ol>
     */
    @Override
    public NotificationParseResult parseNotification(RawNotification raw) {
        String sigHeader = raw.headerIgnoreCase("Stripe-Signature");
        if (sigHeader == null) {
            throw new SecurityException("Stripe webhook 缺少签名头");
        }
        long timestamp = parseTimestamp(sigHeader);
        long toleranceSec = 300; // 5 分钟容忍窗口，防重放
        if (Math.abs(Instant.now().getEpochSecond() - timestamp) > toleranceSec) {
            throw new SecurityException("Stripe webhook 时间戳超出容忍窗口，疑似重放攻击");
        }
        verifyHmac(raw.body(), sigHeader);

        return new NotificationParseResult(
                OutTradeNo.of("MOCK"), "MOCK_TXN", ChannelResultStatus.PENDING,
                "requires_confirmation", Money.ofMinor(0L, Currency.USD),
                "evt_" + System.currentTimeMillis(), "payment", Instant.now(), raw.body());
    }

    private long parseTimestamp(String sigHeader) {
        for (String part : sigHeader.split(",")) {
            if (part.trim().startsWith("t=")) {
                return Long.parseLong(part.trim().substring(2));
            }
        }
        throw new SecurityException("Stripe 签名头缺少时间戳");
    }

    private void verifyHmac(String body, String sigHeader) {
        // TODO 真实实现：HMAC-SHA256 + 常量时间比较
    }


}
