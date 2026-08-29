package com.zx.payment.acquisition.domain.event;

import com.zx.payment.shared.DomainEvent;
import com.zx.payment.shared.Money;

import java.time.Instant;

/**
 * 领域事件：支付成功【且已付清全额】。
 *
 * 注意与"单次尝试成功"区分：部分支付场景下，某次 attempt 成功不代表整单成功，
 * 只有累计已收 == 应付金额时才发这个事件。
 *
 * 订阅方（本上下文内）：商户通知、库存/履约推进。
 * 跨上下文：由应用层在事务提交后，转换成集成事件 PaymentSucceededV1 投递给退款上下文。
 */
public final class PaymentSucceededEvent extends DomainEvent {

    private final String merchantId;
    private final String merchantOrderNo;
    private final Money paidAmount;
    private final String channelTradeNo;
    private final Instant paidAt;

    public PaymentSucceededEvent(String paymentId, String merchantId, String merchantOrderNo,
                                 Money paidAmount, String channelTradeNo, Instant paidAt) {
        super(paymentId);
        this.merchantId = merchantId;
        this.merchantOrderNo = merchantOrderNo;
        this.paidAmount = paidAmount;
        this.channelTradeNo = channelTradeNo;
        this.paidAt = paidAt;
    }

    public String merchantId() { return merchantId; }
    public String merchantOrderNo() { return merchantOrderNo; }
    public Money paidAmount() { return paidAmount; }
    public String channelTradeNo() { return channelTradeNo; }
    public Instant paidAt() { return paidAt; }
}
