package com.demo.payment.adapter.unionpay;

import com.demo.payment.adapter.core.AbstractChannelAdapter;
import com.demo.payment.domain.acquiring.model.vo.OutTradeNo;
import com.demo.payment.domain.channel.model.*;
import com.demo.payment.domain.channel.spi.*;
import com.demo.payment.shared.money.Currency;
import com.demo.payment.shared.money.Money;

import java.time.Instant;
import java.util.Set;

/**
 * 银联全渠道适配器。
 *
 * <h3>通道定位</h3>
 * <p>银联在国内是<b>卡组织</b>角色，因此它天然具备卡组织的典型特征：
 * <ul>
 *   <li><b>支持预授权（auth-capture 两段式）</b>：这是它区别于微信/支付宝的核心能力。
 *       酒店、租车行业必须依赖它。</li>
 *   <li><b>支持撤销（void）</b>：当日撤销不产生退款单、不收手续费。</li>
 *   <li><b>有争议/差错处理</b>：类似国际卡组织的 chargeback，
 *       通过银联的差错平台（贷记调整、例外交易）处理。</li>
 *   <li><b>退款期限 180 天</b>，短于微信/支付宝的 365 天。</li>
 * </ul>
 *
 * <p>因此它是一个非常好的"国内 + 卡组织特性"的教学样本：
 * 同一套抽象下，它既有国内通道的接入形态，又有国际卡组织的资金模型。
 */
public class UnionPayAdapter extends AbstractChannelAdapter {

    private static final ChannelCapability CAPABILITY = new ChannelCapability(
            ChannelCode.UNION_PAY,
            "银联",
            ChannelCapability.AcquiringModel.GATEWAY,
            Set.of(PaymentMethodType.UNION_PAY_CARD, PaymentMethodType.BANK_CARD),
            true,
            true,
            true,
            true,
            180,
            true,
            ChannelCapability.NotifyMode.PUSH_AND_PULL,
            ChannelCapability.IdempotencyMode.MERCHANT_ORDER_NO_ONLY,
            ChannelCapability.SignatureAlgorithm.HMAC_SHA256,
            true,
            Set.of(ChannelCapability.IntegrationMode.REDIRECT_CHECKOUT, ChannelCapability.IntegrationMode.NATIVE_SDK, ChannelCapability.IntegrationMode.QR_CODE),
            Set.of(Currency.CNY),
            1L,
            100000000L,
            java.time.Duration.ofMinutes(60),
            true,
            ChannelCapability.SettlementMode.DEFERRED
    );

    @Override
    public ChannelCode channelCode() {
        return ChannelCode.UNION_PAY;
    }

    @Override
    public ChannelCapability capability() {
        return CAPABILITY;
    }

    @Override
    protected PayResponse doPay(PayCommand command) {
        // TODO 真实实现：银联全渠道统一收单接口
        //   关键字段：txnType(01消费/02预授权)、txnSubType、channelType(07互联网/08移动端)
        String txnType = command.extraParams().getOrDefault("txnType", "01");

        return PayResponse.pending(command.outTradeNo(), cred(
                "txnType", txnType,
                "redirectUrl", "https://unionpay.com/pay?orderId=" + command.outTradeNo().value()
        ));
    }

    @Override
    protected QueryResponse doQuery(QueryCommand command) {
        // TODO 真实实现：银联交易状态查询（必须带上 origQryId）
        return QueryResponse.unknown(command.outTradeNo(), "Mock: 未实现真实查证");
    }

    @Override
    protected CloseResponse doClose(CloseCommand command) {
        return CloseResponse.success(command.outTradeNo());
    }

    /** 撤销：银联当日撤销，资金原路返回且不产生退款单 */
    @Override
    protected CancelResponse doCancel(CancelCommand command) {
        // TODO 真实实现：银联消费撤销接口（需原交易的 queryId）
        return CancelResponse.success(command.outTradeNo());
    }

    /**
     * 请款：预授权完成后扣款。
     *
     * <p>银联预授权完成接口支持"部分完成"，金额小于授权额时差额自动解冻。
     */
    @Override
    protected CaptureResponse doCapture(CaptureCommand command) {
        // TODO 真实实现：银联预授权完成接口
        return CaptureResponse.succeeded(command.outTradeNo().value(), "UPCAP" + System.currentTimeMillis(),
                command.amount());
    }

    @Override
    protected RefundResponse doRefund(RefundCommand command) {
        // TODO 真实实现：银联退货接口（180 天内）
        return RefundResponse.succeeded(command.outRefundNo(), "UPRF" + System.currentTimeMillis(),
                command.amount());
    }

    @Override
    public NotificationParseResult parseNotification(RawNotification raw) {
        // TODO 真实实现：银联回调为 form 表单 + 签名（SHA256/RSA）
        return new NotificationParseResult(
                OutTradeNo.of("MOCK"), "MOCK_TXN", ChannelResultStatus.PENDING, "00",
                Money.ofMinor(0L, Currency.CNY), "MOCK_NOTIFY_ID", "payment",
                Instant.now(), raw.body());
    }


}
