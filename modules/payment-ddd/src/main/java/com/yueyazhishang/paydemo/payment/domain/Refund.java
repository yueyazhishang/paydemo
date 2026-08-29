package com.yueyazhishang.paydemo.payment.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "refunds")
public class Refund {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long paymentId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    private RefundStatus status;

    @Column
    private String externalId;

    @Column
    private Instant createdAt;

    @Column
    private Instant updatedAt;

    protected Refund() {}

    public Refund(Long paymentId, BigDecimal amount) {
        this.paymentId = paymentId;
        this.amount = amount;
        this.status = RefundStatus.INITIATED;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void markProcessing() { this.status = RefundStatus.PROCESSING; this.updatedAt = Instant.now(); }
    public void markCompleted(String externalId) { this.status = RefundStatus.COMPLETED; this.externalId = externalId; this.updatedAt = Instant.now(); }
    public void markFailed() { this.status = RefundStatus.FAILED; this.updatedAt = Instant.now(); }

    public Long getId() { return id; }
    public Long getPaymentId() { return paymentId; }
    public BigDecimal getAmount() { return amount; }
    public RefundStatus getStatus() { return status; }
    public String getExternalId() { return externalId; }
}
