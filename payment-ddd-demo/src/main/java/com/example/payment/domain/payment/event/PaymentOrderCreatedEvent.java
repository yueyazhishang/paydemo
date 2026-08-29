package com.example.payment.domain.payment.event;

import lombok.Getter;

/** 支付单已创建事件 */
@Getter
public class PaymentOrderCreatedEvent extends DomainEvent {

    private final String paymentId;
    private final String bizOrderNo;
    private final String channel;
    private final long amountMinor;
    private final String currency;

    public PaymentOrderCreatedEvent(String paymentId, String bizOrderNo,
                                    String channel, long amountMinor, String currency) {
        super();
        this.paymentId = paymentId;
        this.bizOrderNo = bizOrderNo;
        this.channel = channel;
        this.amountMinor = amountMinor;
        this.currency = currency;
    }
}
