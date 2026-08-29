package com.example.payment.infrastructure.persistence;

import com.example.payment.domain.reconciliation.model.ReconciliationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/**
 * 对账批次 PO。
 */
@Getter
@Setter
@Entity
@Table(name = "reconciliation_batch")
public class ReconciliationBatchPO {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_no", nullable = false, unique = true, length = 64)
    private String batchNo;

    @Column(name = "channel", nullable = false, length = 32)
    private String channel;

    @Column(name = "bill_date", nullable = false)
    private LocalDate billDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ReconciliationStatus status;

    @Column(name = "local_count", nullable = false)
    private Integer localCount;

    @Column(name = "channel_count", nullable = false)
    private Integer channelCount;

    @Column(name = "diff_count", nullable = false)
    private Integer diffCount;
}
