package com.example.payment.domain.payment.event;

import lombok.Getter;

/** 支付成功事件（对账基准、上游通知的触发源） */
@Getter
public class PaymentSucceededEvent extends DomainEvent {

    private final String paymentId;
    private final String bizOrderNo;
    private final String merchantId;
    private final long amountMinor;
    private final String currency;
    private final String channelTradeNo;

    public PaymentSucceededEvent(String paymentId, String bizOrderNo, String merchantId,
                                 long amountMinor, String currency, String channelTradeNo) {
        super();
        this.paymentId = paymentId;
        this.bizOrderNo = bizOrderNo;
        this.merchantId = merchantId;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.channelTradeNo = channelTradeNo;
    }
}
