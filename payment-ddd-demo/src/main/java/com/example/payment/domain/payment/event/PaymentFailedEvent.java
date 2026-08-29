package com.example.payment.domain.payment.event;

import lombok.Getter;

/** 支付失败事件 */
@Getter
public class PaymentFailedEvent extends DomainEvent {

    private final String paymentId;
    private final String bizOrderNo;
    private final String reason;

    public PaymentFailedEvent(String paymentId, String bizOrderNo, String reason) {
        super();
        this.paymentId = paymentId;
        this.bizOrderNo = bizOrderNo;
        this.reason = reason;
    }
}
