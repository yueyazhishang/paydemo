package com.example.payment.domain.reconciliation.model;

import lombok.Getter;

/**
 * 对账差异明细实体（属于 ReconciliationBatch 聚合）。
 */
@Getter
public class ReconciliationItem {

    private String batchNo;
    private DiffType diffType;
    private String bizOrderNo;
    private Long localAmountMinor;
    private Long channelAmountMinor;
    private String remark;

    public static ReconciliationItem of(String batchNo, DiffType type, String bizOrderNo,
                                        Long localAmountMinor, Long channelAmountMinor, String remark) {
        ReconciliationItem item = new ReconciliationItem();
        item.batchNo = batchNo;
        item.diffType = type;
        item.bizOrderNo = bizOrderNo;
        item.localAmountMinor = localAmountMinor;
        item.channelAmountMinor = channelAmountMinor;
        item.remark = remark;
        return item;
    }
}
