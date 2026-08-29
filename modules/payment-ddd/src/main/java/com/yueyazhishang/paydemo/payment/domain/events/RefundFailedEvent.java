package com.yueyazhishang.paydemo.payment.domain.events;

public class RefundFailedEvent {
    private final Long refundId;

    public RefundFailedEvent(Long refundId) {
        this.refundId = refundId;
    }

    public Long getRefundId() {
        return refundId;
    }
}
