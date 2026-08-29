package com.example.payment.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 对账批次 Spring Data 仓储接口。
 */
public interface ReconciliationBatchJpaRepository extends JpaRepository<ReconciliationBatchPO, Long> {

    Optional<ReconciliationBatchPO> findByBatchNo(String batchNo);
}
