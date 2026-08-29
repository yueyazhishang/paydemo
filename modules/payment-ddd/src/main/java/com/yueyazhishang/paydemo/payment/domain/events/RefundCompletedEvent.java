package com.yueyazhishang.paydemo.payment.domain.events;

public class RefundCompletedEvent {
    private final Long refundId;
    private final String externalId;

    public RefundCompletedEvent(Long refundId, String externalId) {
        this.refundId = refundId;
        this.externalId = externalId;
    }

    public Long getRefundId() {
        return refundId;
    }

    public String getExternalId() {
        return externalId;
    }
}
