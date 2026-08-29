package com.example.payment.domain.payment.event;

import lombok.Getter;

/** 退款成功事件 */
@Getter
public class RefundSucceededEvent extends DomainEvent {

    private final String refundId;
    private final String paymentId;
    private final long refundAmountMinor;
    private final String currency;
    private final String channelRefundNo;

    public RefundSucceededEvent(String refundId, String paymentId,
                                long refundAmountMinor, String currency, String channelRefundNo) {
        super();
        this.refundId = refundId;
        this.paymentId = paymentId;
        this.refundAmountMinor = refundAmountMinor;
        this.currency = currency;
        this.channelRefundNo = channelRefundNo;
    }
}
