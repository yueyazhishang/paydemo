package com.example.payment.domain.reconciliation.repository;

import com.example.payment.domain.reconciliation.model.ReconciliationItem;

import java.util.List;

/**
 * 对账差异明细仓储接口。
 */
public interface ReconciliationItemRepository {

    List<ReconciliationItem> saveAll(List<ReconciliationItem> items);

    List<ReconciliationItem> findByBatchNo(String batchNo);
}
