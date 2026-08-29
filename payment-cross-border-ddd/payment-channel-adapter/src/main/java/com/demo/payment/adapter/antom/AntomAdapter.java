package com.demo.payment.adapter.antom;

import com.demo.payment.adapter.core.AbstractChannelAdapter;
import com.demo.payment.domain.acquiring.model.vo.OutTradeNo;
import com.demo.payment.domain.channel.model.*;
import com.demo.payment.domain.channel.spi.*;
import com.demo.payment.shared.money.Currency;
import com.demo.payment.shared.money.Money;

import java.time.Instant;
import java.util.Set;

/**
 * Antom 适配器（蚂蚁国际 Ant International）。
 *
 * <h3>它是"通道里的通道"</h3>
 * <p>Antom 是<b>聚合收单平台</b>（{@code AcquiringModel.AGGREGATOR}），
 * 一个通道背后挂着 300+ 支付方式、200+ 市场、100+ 币种。
 * 这带来一个特殊的建模问题：<b>嵌套通道</b>。
 *
 * <p>处理方式：把 Antom 建模成"支持 N 种支付方式的单一通道"。
 * 上层按支付方式选通道时，Antom 会因为支持 BNPL、现金支付等
 * 而自然进入候选列表 —— 能力矩阵的建模方式天然支持这一点，
 * 无需为聚合平台单独开一套机制。
 *
 * <h3>关键差异</h3>
 * <ol>
 *   <li><b>paymentRequestId 做幂等</b>（{@code BUSINESS_FIELD} 模式）：
 *       幂等键放在业务字段里，而不是请求头。这是第三种幂等形态，
 *       与 Stripe 的头幂等、微信的无幂等都不一样，适配层必须分别处理。</li>
 *   <li><b>三种集成形态</b>：Payment Element（内嵌组件）/ Checkout Page（托管页）/
 *       API-only（纯 API）。上层需要按场景选择 —— 这体现在
 *       {@code integrationModes} 能力声明里。</li>
 *   <li><b>APM 差异极大</b>：不同支付方式有各自的特殊要求。
 *       例如文档明确指出 PayPay 的 {@code paymentRedirectUrl} 有长度限制、
 *       退款次数不能超过 20 次。这类"长尾约束"无法全部建模，
 *       只能落到 {@code extraParams} 逃生舱 + 适配层文档。</li>
 *   <li><b>退款期限不统一</b>：BNPL 类的 Tamara 只有 120 天、Paidy 365 天、
 *       而 Pagaleve 只有 90 天。能力矩阵里的 180 天是<b>保守兜底值</b>，
 *       生产环境应按具体支付方式细分。</li>
 * </ol>
 */
public class AntomAdapter extends AbstractChannelAdapter {

    private static final ChannelCapability CAPABILITY = new ChannelCapability(
            ChannelCode.ANTOM,
            "Antom",
            ChannelCapability.AcquiringModel.AGGREGATOR,
            Set.of(PaymentMethodType.BANK_CARD, PaymentMethodType.APPLE_PAY, PaymentMethodType.GOOGLE_PAY, PaymentMethodType.BNPL, PaymentMethodType.ONLINE_BANKING, PaymentMethodType.CASH, PaymentMethodType.REAL_TIME_PAYMENT, PaymentMethodType.ALIPAY_WALLET, PaymentMethodType.PAYPAL_WALLET),
            true,
            true,
            true,
            true,
            180,
            true,
            ChannelCapability.NotifyMode.PUSH_AND_PULL,
            ChannelCapability.IdempotencyMode.BUSINESS_FIELD,
            ChannelCapability.SignatureAlgorithm.HMAC_SHA256,
            false,
            Set.of(ChannelCapability.IntegrationMode.EMBEDDED_ELEMENT, ChannelCapability.IntegrationMode.REDIRECT_CHECKOUT, ChannelCapability.IntegrationMode.API_ONLY),
            Set.of(Currency.USD, Currency.EUR, Currency.GBP, Currency.JPY, Currency.SGD, Currency.THB, Currency.IDR, Currency.KRW, Currency.BRL, Currency.PHP, Currency.SAR, Currency.HKD, Currency.AUD),
            1L,
            99999999L,
            java.time.Duration.ofMinutes(60),
            true,
            ChannelCapability.SettlementMode.DEFERRED
    );

    @Override
    public ChannelCode channelCode() {
        return ChannelCode.ANTOM;
    }

    @Override
    public ChannelCapability capability() {
        return CAPABILITY;
    }

    /**
     * 发起支付。
     *
     * <p><b>幂等实现</b>：把 {@code paymentRequestId} 作为幂等字段放进请求体。
     * 这是 {@code BUSINESS_FIELD} 幂等模式的标准做法。
     */
    @Override
    protected PayResponse doPay(PayCommand command) {
        String paymentMethodType = mapToAntomPaymentMethod(command.paymentMethod());
        String integrationMode = command.extraParams().getOrDefault("integrationMode", "CHECKOUT_PAGE");

        // TODO 真实实现：
        //   Checkout Page    → POST /ams/api/v1/payments/createPaymentSession
        //   Payment Element  → 同上，但客户端用 paymentSessionData 渲染组件
        //   API-only         → POST /ams/api/v1/payments/pay
        //   请求体必须包含 paymentRequestId（幂等键）
        //   响应：paymentSessionData / redirectUrl / normalUrl / paymentId

        return PayResponse.pending(command.outTradeNo(), cred(
                "paymentMethodType", paymentMethodType,
                "integrationMode", integrationMode,
                "paymentRequestId", command.idempotencyKey(),
                "paymentSessionData", "MOCK_SESSION_DATA",
                "normalUrl", "https://antom.com/checkout?session=MOCK",
                "paymentId", "ANTOM_" + System.currentTimeMillis()
        ));
    }

    /**
     * 支付方式 → Antom 的 paymentMethodType 映射。
     *
     * <p>Antom 用字符串标识支付方式（如 "CARD"、"ALIPAY_CN"、
     * "GCASH"、"TRUEMONEY"、"KLARNA"），且同一类别下还有细分
     * （如 "CARD" 下有 VISA / MASTERCARD 品牌）。
     * 这里只做类别级映射，品牌级由 {@code extraParams} 传递。
     */
    private String mapToAntomPaymentMethod(PaymentMethodType type) {
        return switch (type) {
            case BANK_CARD -> "CARD";
            case APPLE_PAY -> "APPLEPAY";
            case GOOGLE_PAY -> "GOOGLEPAY";
            case ALIPAY_WALLET -> "ALIPAY_CN";
            case PAYPAL_WALLET -> "PAYPAL";
            case BNPL -> "BNPL";
            case ONLINE_BANKING -> "ONLINE_BANKING";
            case CASH -> "CASH";
            case REAL_TIME_PAYMENT -> "REALTIME_PAYMENT";
            default -> throw new IllegalArgumentException("Antom 不支持的支付方式: " + type);
        };
    }

    @Override
    protected QueryResponse doQuery(QueryCommand command) {
        // TODO 真实实现：POST /ams/api/v1/payments/inquiryPayment
        //   用 paymentRequestId 查询（即幂等键，体现其双重作用）
        return QueryResponse.unknown(command.outTradeNo(), "Mock: 未实现真实查证");
    }

    @Override
    protected CloseResponse doClose(CloseCommand command) {
        // TODO 真实实现：POST /ams/api/v1/payments/cancel
        return CloseResponse.success(command.outTradeNo());
    }

    @Override
    protected CancelResponse doCancel(CancelCommand command) {
        // TODO 真实实现：POST /ams/api/v1/payments/cancel（仅卡类支持）
        return CancelResponse.success(command.outTradeNo());
    }

    @Override
    protected CaptureResponse doCapture(CaptureCommand command) {
        // TODO 真实实现：POST /ams/api/v1/payments/capture
        return CaptureResponse.succeeded(command.outTradeNo().value(), "ANTOMCAP" + System.currentTimeMillis(),
                command.amount());
    }

    /**
     * 退款。
     *
     * <p><b>注意 APM 的特殊限制</b>：例如 PayPay 的退款次数不能超过 20 次。
     * 这类约束无法在能力矩阵中穷举，实践中需要：
     * <ul>
     *   <li>在 {@code extraParams} 中传递支付方式细分类型</li>
     *   <li>适配层内部维护一张"支付方式 → 特殊约束"的表</li>
     *   <li>超限前主动拦截并给出明确错误</li>
     * </ul>
     */
    @Override
    protected RefundResponse doRefund(RefundCommand command) {
        // TODO 真实实现：POST /ams/api/v1/payments/refund
        //   请求体：refundRequestId（退款幂等键）+ paymentId + refundAmount
        return RefundResponse.succeeded(command.outRefundNo(), "ANTOMRF" + System.currentTimeMillis(),
                command.amount());
    }

    @Override
    public NotificationParseResult parseNotification(RawNotification raw) {
        // TODO 真实实现：解析 JSON + 校验 HMAC-SHA256 签名（签名在请求头）
        String signature = raw.headerIgnoreCase("Signature");
        if (signature == null) {
            throw new SecurityException("Antom 回调缺少签名头");
        }
        return new NotificationParseResult(
                OutTradeNo.of("MOCK"), "MOCK_TXN", ChannelResultStatus.PENDING, "PROCESSING",
                Money.ofMinor(0L, Currency.USD), "ANTOM_NOTIFY_" + System.currentTimeMillis(),
                "payment", Instant.now(), raw.body());
    }


}
