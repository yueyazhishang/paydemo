package com.example.payment.infrastructure.persistence;

import com.example.payment.domain.reconciliation.model.ReconciliationItem;
import com.example.payment.domain.reconciliation.repository.ReconciliationItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 对账差异明细仓储端口实现。
 */
@Repository
@RequiredArgsConstructor
public class ReconciliationItemRepositoryImpl implements ReconciliationItemRepository {

    private final ReconciliationItemJpaRepository jpaRepository;

    @Override
    public List<ReconciliationItem> saveAll(List<ReconciliationItem> items) {
        List<ReconciliationItemPO> pos = items.stream().map(item -> {
            ReconciliationItemPO po = new ReconciliationItemPO();
            po.setBatchNo(item.getBatchNo());
            po.setDiffType(item.getDiffType());
            po.setBizOrderNo(item.getBizOrderNo());
            po.setLocalAmount(item.getLocalAmountMinor());
            po.setChannelAmount(item.getChannelAmountMinor());
            po.setRemark(item.getRemark());
            return po;
        }).toList();
        jpaRepository.saveAll(pos);
        return items;
    }

    @Override
    public List<ReconciliationItem> findByBatchNo(String batchNo) {
        return jpaRepository.findByBatchNo(batchNo).stream()
                .map(po -> ReconciliationItem.of(po.getBatchNo(), po.getDiffType(),
                        po.getBizOrderNo(), po.getLocalAmount(), po.getChannelAmount(), po.getRemark()))
                .toList();
    }
}
