package com.yueyazhishang.paydemo.payment.domain.events;

public class RefundInitiatedEvent {
    private final Long refundId;
    private final Long paymentId;

    public RefundInitiatedEvent(Long refundId, Long paymentId) {
        this.refundId = refundId;
        this.paymentId = paymentId;
    }

    public Long getRefundId() {
        return refundId;
    }

    public Long getPaymentId() {
        return paymentId;
    }
}
