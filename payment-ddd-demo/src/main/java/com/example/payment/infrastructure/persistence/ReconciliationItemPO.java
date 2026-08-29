package com.example.payment.infrastructure.persistence;

import com.example.payment.domain.reconciliation.model.DiffType;
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

/**
 * 对账差异明细 PO。
 */
@Getter
@Setter
@Entity
@Table(name = "reconciliation_item")
public class ReconciliationItemPO {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_no", nullable = false, length = 64)
    private String batchNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "diff_type", nullable = false, length = 32)
    private DiffType diffType;

    @Column(name = "biz_order_no", length = 64)
    private String bizOrderNo;

    @Column(name = "local_amount")
    private Long localAmount;

    @Column(name = "channel_amount")
    private Long channelAmount;

    @Column(name = "remark")
    private String remark;
}
