package com.yueyazhishang.paydemo.payment.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "payments")
public class Payment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String orderId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    private PaymentStatus status;

    @Column
    private String externalId; // id from channel

    @Column
    private Instant createdAt;

    @Column
    private Instant updatedAt;

    @Version
    private Long version;

    protected Payment() {
    }

    public Payment(String orderId, BigDecimal amount, String currency) {
        this.orderId = orderId;
        this.amount = amount;
        this.currency = currency;
        this.status = PaymentStatus.CREATED;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    // domain behaviors
    public void markPending() {
        if (this.status != PaymentStatus.CREATED) {
            throw new IllegalStateException("Payment must be in CREATED to mark PENDING");
        }
        this.status = PaymentStatus.PENDING;
        this.updatedAt = Instant.now();
    }

    public void markAuthorized(String externalId) {
        this.status = PaymentStatus.AUTHORIZED;
        this.externalId = externalId;
        this.updatedAt = Instant.now();
    }

    public void markCompleted() {
        this.status = PaymentStatus.COMPLETED;
        this.updatedAt = Instant.now();
    }

    public void markFailed() {
        this.status = PaymentStatus.FAILED;
        this.updatedAt = Instant.now();
    }

    // getters & setters
    public Long getId() {
        return id;
    }

    public String getOrderId() {
        return orderId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public String getExternalId() {
        return externalId;
    }
}
