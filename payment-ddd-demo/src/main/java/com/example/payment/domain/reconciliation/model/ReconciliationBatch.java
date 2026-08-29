package com.example.payment.domain.reconciliation.model;

import lombok.Getter;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 对账批次聚合根：一次「渠道 × 日期」的 T+1 核对任务。
 */
@Getter
public class ReconciliationBatch {

    private String batchNo;
    private String channel;
    private LocalDate billDate;
    private ReconciliationStatus status;
    private int localCount;
    private int channelCount;
    private int diffCount;

    /** 差异明细（聚合内实体，随批次一起持久化） */
    private final List<ReconciliationItem> items = new ArrayList<>();

    public static ReconciliationBatch create(String channel, LocalDate billDate) {
        ReconciliationBatch batch = new ReconciliationBatch();
        batch.batchNo = channel + "_" + billDate;
        batch.channel = channel;
        batch.billDate = billDate;
        batch.status = ReconciliationStatus.INIT;
        return batch;
    }

    public void startDownloading() {
        this.status = ReconciliationStatus.DOWNLOADING;
    }

    public void startChecking() {
        this.status = ReconciliationStatus.CHECKING;
    }

    /** 完成核对：登记双方笔数与差异明细 */
    public void complete(int localCount, int channelCount, List<ReconciliationItem> diffItems) {
        this.status = ReconciliationStatus.DONE;
        this.localCount = localCount;
        this.channelCount = channelCount;
        this.diffCount = diffItems.size();
        this.items.addAll(diffItems);
    }

    public boolean hasDiscrepancy() {
        return diffCount > 0;
    }
}
