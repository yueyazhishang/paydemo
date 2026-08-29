package com.yueyazhishang.paydemo.payment.domain;

public enum PaymentStatus {
    CREATED,
    PENDING,
    AUTHORIZED,
    COMPLETED,
    FAILED,
    REFUNDED,
    CANCELLED
}
