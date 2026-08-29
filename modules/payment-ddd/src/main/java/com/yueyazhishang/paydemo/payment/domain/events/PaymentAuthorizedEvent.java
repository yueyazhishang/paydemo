package com.yueyazhishang.paydemo.payment.domain.events;

public class PaymentAuthorizedEvent {
    private final Long paymentId;
    private final String externalId;

    public PaymentAuthorizedEvent(Long paymentId, String externalId) {
        this.paymentId = paymentId;
        this.externalId = externalId;
    }

    public Long getPaymentId() {
        return paymentId;
    }

    public String getExternalId() {
        return externalId;
    }
}
