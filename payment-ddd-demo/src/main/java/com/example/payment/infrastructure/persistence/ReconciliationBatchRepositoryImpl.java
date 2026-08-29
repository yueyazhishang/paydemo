package com.example.payment.infrastructure.persistence;

import com.example.payment.domain.reconciliation.model.ReconciliationBatch;
import com.example.payment.domain.reconciliation.repository.ReconciliationBatchRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 对账批次仓储端口实现。
 */
@Repository
@RequiredArgsConstructor
public class ReconciliationBatchRepositoryImpl implements ReconciliationBatchRepository {

    private final ReconciliationBatchJpaRepository jpaRepository;

    @Override
    public ReconciliationBatch save(ReconciliationBatch batch) {
        ReconciliationBatchPO po = jpaRepository.findByBatchNo(batch.getBatchNo())
                .orElseGet(() -> {
                    ReconciliationBatchPO newPO = new ReconciliationBatchPO();
                    newPO.setBatchNo(batch.getBatchNo());
                    newPO.setChannel(batch.getChannel());
                    newPO.setBillDate(batch.getBillDate());
                    return newPO;
                });
        po.setStatus(batch.getStatus());
        po.setLocalCount(batch.getLocalCount());
        po.setChannelCount(batch.getChannelCount());
        po.setDiffCount(batch.getDiffCount());
        jpaRepository.save(po);
        return batch;
    }

    @Override
    public Optional<ReconciliationBatch> findByBatchNo(String batchNo) {
        return jpaRepository.findByBatchNo(batchNo).map(po -> {
            ReconciliationBatch batch = ReconciliationBatch.create(po.getChannel(), po.getBillDate());
            batch.startDownloading();
            batch.startChecking();
            batch.complete(po.getLocalCount(), po.getChannelCount(),
                    java.util.Collections.emptyList());
            return batch;
        });
    }
}
