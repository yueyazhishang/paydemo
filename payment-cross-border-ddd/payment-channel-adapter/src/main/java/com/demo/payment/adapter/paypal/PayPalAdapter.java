package com.demo.payment.adapter.paypal;

import com.demo.payment.adapter.core.AbstractChannelAdapter;
import com.demo.payment.domain.acquiring.model.vo.OutTradeNo;
import com.demo.payment.domain.channel.model.*;
import com.demo.payment.domain.channel.spi.*;
import com.demo.payment.shared.money.Currency;
import com.demo.payment.shared.money.Money;

import java.time.Instant;
import java.util.Set;

/**
 * PayPal 适配器（Orders v2）。
 *
 * <h3>模型差异</h3>
 * <p>PayPal 的 Orders v2 是标准的<b>两段式</b>：
 * <pre>
 *   POST /v2/checkout/orders         创建订单（intent=AUTHORIZE 或 CAPTURE）
 *     ↓ 买家在 PayPal 页面确认
 *   POST /v2/checkout/orders/{id}/authorize   （intent=AUTHORIZE 时）
 *   POST /v2/checkout/orders/{id}/capture     （intent=CAPTURE 时，直接扣款）
 * </pre>
 *
 * <h3>三个坑</h3>
 * <ol>
 *   <li><b>Webhook 无法本地验签</b>：PayPal 不提供像 Stripe 那样的 HMAC 签名头。
 *       必须<b>回调 PayPal 的 verify-webhook-signature 接口</b>验签 ——
 *       这意味着验签本身是一次网络调用，要考虑超时与重试，
 *       并且验签失败时如何处理是个真实的工程难题。</li>
 *   <li><b>退款期限 180 天</b>：超过后 API 无法退款，只能走线下。</li>
 *   <li><b>争议（dispute）流程复杂</b>：PayPal 的争议/补偿申请有严格的证据提交时限，
 *       需要独立的争议管理模块，不能混在退款里。</li>
 * </ol>
 *
 * <h3>金额格式</h3>
 * <p>PayPal 用<b>十进制字符串</b>（"10.00"），而不是最小单位整数。
 * 这是它与微信/Stripe 的显著差异 —— 适配层必须做 Money → "10.00" 的转换，
 * 且要按币种指数处理（JPY 是 "100" 而非 "100.00"）。
 */
public class PayPalAdapter extends AbstractChannelAdapter {

    private static final ChannelCapability CAPABILITY = new ChannelCapability(
            ChannelCode.PAYPAL,
            "PayPal",
            ChannelCapability.AcquiringModel.WALLET,
            Set.of(PaymentMethodType.PAYPAL_WALLET, PaymentMethodType.BANK_CARD),
            true,
            true,
            true,
            true,
            180,
            true,
            ChannelCapability.NotifyMode.PUSH_AND_PULL,
            ChannelCapability.IdempotencyMode.HEADER_REQUEST_ID,
            ChannelCapability.SignatureAlgorithm.HMAC_SHA256,
            false,
            Set.of(ChannelCapability.IntegrationMode.REDIRECT_CHECKOUT, ChannelCapability.IntegrationMode.EMBEDDED_ELEMENT),
            Set.of(Currency.USD, Currency.EUR, Currency.GBP, Currency.JPY, Currency.AUD, Currency.HKD, Currency.SGD),
            1L,
            6000000L,
            java.time.Duration.ofMinutes(180),
            true,
            ChannelCapability.SettlementMode.IMMEDIATE
    );

    @Override
    public ChannelCode channelCode() {
        return ChannelCode.PAYPAL;
    }

    @Override
    public ChannelCapability capability() {
        return CAPABILITY;
    }

    @Override
    protected PayResponse doPay(PayCommand command) {
        // 金额格式化：PayPal 要求十进制字符串，且小数位必须匹配币种指数
        String amountStr = command.amount().majorValue()
                .setScale(command.amount().currency().exponent()).toPlainString();

        // TODO 真实实现：POST /v2/checkout/orders
        //   body: {intent: "CAPTURE", purchase_units:[{amount:{currency_code, value},
        //         reference_id: outTradeNo}], application_context:{return_url, cancel_url}}
        //   请求头：PayPal-Request-Id 做幂等

        return PayResponse.pending(command.outTradeNo(), cred(
                "orderId", "PAYPAL_ORDER_" + System.currentTimeMillis(),
                "approvalUrl", "https://www.paypal.com/checkoutnow?token=MOCK",
                "amount", amountStr
        ));
    }

    @Override
    protected QueryResponse doQuery(QueryCommand command) {
        // TODO 真实实现：GET /v2/checkout/orders/{id} 或 /v2/payments/captures/{id}
        return QueryResponse.unknown(command.outTradeNo(), "Mock: 未实现真实查证");
    }

    @Override
    protected CloseResponse doClose(CloseCommand command) {
        // PayPal 没有显式关单接口：订单超时自动过期（通常 3 小时）
        // 这是"通道能力缺失"的典型例子 —— 只能靠本地状态机处理
        return CloseResponse.success(command.outTradeNo());
    }

    /** 撤销授权：仅 intent=AUTHORIZE 且未请款时可调用 */
    @Override
    protected CancelResponse doCancel(CancelCommand command) {
        // TODO 真实实现：POST /v2/payments/authorizations/{id}/void
        return CancelResponse.success(command.outTradeNo());
    }

    @Override
    protected CaptureResponse doCapture(CaptureCommand command) {
        // TODO 真实实现：POST /v2/checkout/orders/{id}/capture
        //   支持部分请款，剩余金额自动释放
        return CaptureResponse.succeeded(command.outTradeNo().value(), "PPCAP" + System.currentTimeMillis(),
                command.amount());
    }

    @Override
    protected RefundResponse doRefund(RefundCommand command) {
        // TODO 真实实现：POST /v2/payments/captures/{captureId}/refund
        //   请求头带 PayPal-Request-Id 幂等
        return RefundResponse.succeeded(command.outRefundNo(), "PPRF" + System.currentTimeMillis(),
                command.amount());
    }

    /**
     * 回调解析。
     *
     * <p><b>关键差异：验签需要回调 PayPal 接口。</b>
     * 这意味着 parseNotification 是一次"有网络 IO 的操作"，
     * 需要考虑：超时怎么办？PayPal 不可用时要不要放行？
     * 生产上的常见折中是：验签接口超时时先落库标记 UNVERIFIED，
     * 由异步任务补验，验签失败再回滚业务状态。
     */
    @Override
    public NotificationParseResult parseNotification(RawNotification raw) {
        // TODO 真实实现：POST /v1/notifications/verify-webhook-signature
        //   body: {auth_algo, cert_url, transmission_id, transmission_sig,
        //          transmission_time, webhook_id, webhook_event}
        boolean verified = verifyWebhookRemotely(raw);
        if (!verified) {
            throw new SecurityException("PayPal 回调验签失败");
        }
        return new NotificationParseResult(
                OutTradeNo.of("MOCK"), "MOCK_TXN", ChannelResultStatus.PENDING, "CREATED",
                Money.ofMinor(0L, Currency.USD), "MOCK_NOTIFY_ID", "payment",
                Instant.now(), raw.body());
    }

    private boolean verifyWebhookRemotely(RawNotification raw) {
        return raw.body() != null && raw.body().contains("event_type");
    }


}
