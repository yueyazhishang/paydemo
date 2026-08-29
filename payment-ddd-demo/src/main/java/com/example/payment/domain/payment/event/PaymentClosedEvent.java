package com.example.payment.domain.payment.event;

import lombok.Getter;

/** 支付单关闭事件 */
@Getter
public class PaymentClosedEvent extends DomainEvent {

    private final String paymentId;
    private final String bizOrderNo;

    public PaymentClosedEvent(String paymentId, String bizOrderNo) {
        super();
        this.paymentId = paymentId;
        this.bizOrderNo = bizOrderNo;
    }
}
