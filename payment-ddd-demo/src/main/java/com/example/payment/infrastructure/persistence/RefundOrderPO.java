package com.example.payment.infrastructure.persistence;

import com.example.payment.domain.payment.model.RefundStatus;
import com.example.payment.domain.shared.Currency;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

/**
 * 退款单持久化对象（PO）。
 */
@Getter
@Setter
@Entity
@Table(name = "refund_order")
public class RefundOrderPO {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "refund_id", nullable = false, unique = true, length = 64)
    private String refundId;

    @Column(name = "payment_id", nullable = false, length = 64)
    private String paymentId;

    @Column(name = "refund_amount", nullable = false)
    private Long refundAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency", nullable = false, length = 8)
    private Currency currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private RefundStatus status;

    @Column(name = "channel_refund_no", length = 64)
    private String channelRefundNo;

    @Column(name = "reason")
    private String reason;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
