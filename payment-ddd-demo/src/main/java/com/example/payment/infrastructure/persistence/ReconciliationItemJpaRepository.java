package com.example.payment.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 对账差异明细 Spring Data 仓储接口。
 */
public interface ReconciliationItemJpaRepository extends JpaRepository<ReconciliationItemPO, Long> {

    List<ReconciliationItemPO> findByBatchNo(String batchNo);
}
