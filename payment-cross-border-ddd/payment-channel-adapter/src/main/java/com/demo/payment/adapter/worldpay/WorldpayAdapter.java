package com.demo.payment.adapter.worldpay;

import com.demo.payment.adapter.core.AbstractChannelAdapter;
import com.demo.payment.domain.acquiring.model.vo.OutTradeNo;
import com.demo.payment.domain.channel.model.*;
import com.demo.payment.domain.channel.spi.*;
import com.demo.payment.shared.money.Currency;
import com.demo.payment.shared.money.Money;

import java.time.Instant;
import java.util.Set;

/**
 * Worldpay 适配器（XML paymentService v1.4）。
 *
 * <h3>最大的差异：它用 XML，而且很传统</h3>
 * <p>Worldpay 的老牌网关接口是 XML 协议：
 * <pre>{@code
 *   <paymentService version="1.4" merchantCode="YOUR_MERCHANT_CODE">
 *     <submit>
 *       <order orderCode="ORDER123">
 *         <description>...</description>
 *         <amount value="1000" currencyCode="GBP" exponent="2"/>
 *         <paymentDetails>
 *           <VISA-SSL><cardNumber>...</cardNumber></VISA-SSL>
 *         </paymentDetails>
 *       </order>
 *     </submit>
 *   </paymentService>
 * }</pre>
 *
 * <p>这个差异给抽象带来的挑战是真实的：
 * <ul>
 *   <li><b>报文是 XML</b>：需要 XML 序列化/反序列化，与 JSON 通道完全不同。
 *       但这是<b>适配层内部的事</b>，对上层不可见 —— 这正是分层的价值。</li>
 *   <li><b>金额带 exponent 属性</b>：{@code <amount value="1000" currencyCode="GBP" exponent="2"/>}。
 *       Worldpay 显式声明小数位数，这与 Money 内部按币种指数存储的设计天然对应，
 *       但适配层必须正确填充 exponent（JPY 要填 0）。</li>
 *   <li><b>支付方式用元素名区分</b>：{@code <VISA-SSL>}、{@code <APPLEPAY-SSL>}、
 *       {@code <MASTERCARD-SSL>} —— 卡种是 XML 元素名，不能简单映射成字段。</li>
 *   <li><b>没有幂等头</b>：靠 orderCode 唯一性兜底，重试前必须查单。</li>
 * </ul>
 *
 * <p><b>这个适配器的存在，是对"统一抽象能否成立"的最好验证：</b>
 * 连 XML 这种形态都能被 {@link com.demo.payment.domain.channel.spi.PaymentChannelPort}
 * 收敛进去，说明抽象是站得住的。
 */
public class WorldpayAdapter extends AbstractChannelAdapter {

    private static final ChannelCapability CAPABILITY = new ChannelCapability(
            ChannelCode.WORLDPAY,
            "Worldpay",
            ChannelCapability.AcquiringModel.CARD_ACQUIRING,
            Set.of(PaymentMethodType.BANK_CARD, PaymentMethodType.APPLE_PAY, PaymentMethodType.GOOGLE_PAY),
            true,
            true,
            true,
            true,
            null,
            true,
            ChannelCapability.NotifyMode.PUSH_AND_PULL,
            ChannelCapability.IdempotencyMode.MERCHANT_ORDER_NO_ONLY,
            ChannelCapability.SignatureAlgorithm.WORLDPAY_MAC,
            false,
            Set.of(ChannelCapability.IntegrationMode.API_ONLY, ChannelCapability.IntegrationMode.REDIRECT_CHECKOUT),
            Set.of(Currency.GBP, Currency.USD, Currency.EUR, Currency.JPY, Currency.AUD),
            1L,
            99999999L,
            java.time.Duration.ofMinutes(1440),
            true,
            ChannelCapability.SettlementMode.DEFERRED
    );

    @Override
    public ChannelCode channelCode() {
        return ChannelCode.WORLDPAY;
    }

    @Override
    public ChannelCapability capability() {
        return CAPABILITY;
    }

    @Override
    protected PayResponse doPay(PayCommand command) {
        Money amount = command.amount();
        // Worldpay 要求显式声明 exponent
        int exponent = amount.currency().exponent();
        long value = amount.minorUnits();

        // TODO 真实实现：POST XML 到 Worldpay 网关
        //   组装：<amount value="{value}" currencyCode="{code}" exponent="{exponent}"/>
        //   卡种决定元素名：VISA-SSL / MASTERCARD-SSL / AMEX-SSL / APPLEPAY-SSL
        String cardElement = resolveCardElement(command);

        return PayResponse.pending(command.outTradeNo(), cred(
                "orderCode", command.outTradeNo().value(),
                "cardElement", cardElement,
                "amountValue", String.valueOf(value),
                "exponent", String.valueOf(exponent),
                "xml", "<paymentService version=\"1.4\"><submit><order orderCode=\""
                        + command.outTradeNo().value() + "\">"
                        + "<amount value=\"" + value + "\" currencyCode=\""
                        + amount.currency().code() + "\" exponent=\"" + exponent + "\"/>"
                        + "<paymentDetails><" + cardElement + ">...</" + cardElement + ">"
                        + "</paymentDetails></order></submit></paymentService>"
        ));
    }

    /**
     * 卡种 → XML 元素名映射。
     *
     * <p>这是 Worldpay 特有的"用结构表达类型"的设计，
     * 适配层必须做这层转换，让上层只看到统一的 {@code paymentMethod}。
     */
    private String resolveCardElement(PayCommand command) {
        String brand = command.extraParams().getOrDefault("cardBrand", "VISA");
        return switch (brand.toUpperCase()) {
            case "VISA" -> "VISA-SSL";
            case "MASTERCARD" -> "MASTERCARD-SSL";
            case "AMEX" -> "AMEX-SSL";
            case "APPLEPAY" -> "APPLEPAY-SSL";
            default -> throw new IllegalArgumentException("Unsupported card brand: " + brand);
        };
    }

    @Override
    protected QueryResponse doQuery(QueryCommand command) {
        // TODO 真实实现：发送 <inquiry><orderInquiry orderCode="..."/></inquiry>
        return QueryResponse.unknown(command.outTradeNo(), "Mock: 未实现真实查证");
    }

    @Override
    protected CloseResponse doClose(CloseCommand command) {
        // TODO 真实实现：发送 <modify><cancelReceived/></modify>
        return CloseResponse.success(command.outTradeNo());
    }

    @Override
    protected CancelResponse doCancel(CancelCommand command) {
        // TODO 真实实现：发送 <modify><cancel/></modify>（当日撤销）
        return CancelResponse.success(command.outTradeNo());
    }

    @Override
    protected CaptureResponse doCapture(CaptureCommand command) {
        // TODO 真实实现：发送 <modify><capture><amount .../></capture></modify>
        return CaptureResponse.succeeded(command.outTradeNo().value(), "WPCAP" + System.currentTimeMillis(),
                command.amount());
    }

    @Override
    protected RefundResponse doRefund(RefundCommand command) {
        // TODO 真实实现：发送 <modify><refund><amount .../></refund></modify>
        return RefundResponse.succeeded(command.outRefundNo(), "WPRF" + System.currentTimeMillis(),
                command.amount());
    }

    /**
     * 回调解析（Worldpay 的通知也是 XML）。
     *
     * <p>Worldpay 的通知分为 payment / refund / chargeback 几类，
     * 通过 XML 元素区分，需要解析后映射到统一的 {@code notifyType}。
     */
    @Override
    public NotificationParseResult parseNotification(RawNotification raw) {
        if (raw.body() == null || !raw.body().contains("<paymentService")) {
            throw new IllegalArgumentException("非法 Worldpay 通知报文");
        }
        // TODO 真实实现：解析 XML + 校验 MAC
        verifyMac(raw.body());

        return new NotificationParseResult(
                OutTradeNo.of("MOCK"), "MOCK_TXN", ChannelResultStatus.PENDING,
                "AUTHORISED", Money.ofMinor(0L, Currency.GBP),
                "WP_NOTIFY_" + System.currentTimeMillis(), "payment", Instant.now(), raw.body());
    }

    private void verifyMac(String body) {
        // TODO 真实实现：用商户 MAC 密钥校验（Worldpay 的签名机制）
    }


}
