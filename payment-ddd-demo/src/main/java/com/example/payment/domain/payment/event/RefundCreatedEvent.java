package com.example.payment.domain.payment.event;

import lombok.Getter;

/** 退款单已创建事件 */
@Getter
public class RefundCreatedEvent extends DomainEvent {

    private final String refundId;
    private final String paymentId;
    private final long refundAmountMinor;
    private final String currency;

    public RefundCreatedEvent(String refundId, String paymentId, long refundAmountMinor, String currency) {
        super();
        this.refundId = refundId;
        this.paymentId = paymentId;
        this.refundAmountMinor = refundAmountMinor;
        this.currency = currency;
    }
}
