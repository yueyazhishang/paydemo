package com.example.payment.domain.reconciliation.repository;

import com.example.payment.domain.reconciliation.model.ReconciliationBatch;

import java.util.Optional;

/**
 * 对账批次仓储接口。
 */
public interface ReconciliationBatchRepository {

    ReconciliationBatch save(ReconciliationBatch batch);

    Optional<ReconciliationBatch> findByBatchNo(String batchNo);
}
