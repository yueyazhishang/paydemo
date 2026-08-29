package com.example.payment.infrastructure.persistence;

import com.example.payment.domain.payment.model.MerchantNotifyTask;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 上游通知任务 Spring Data 仓储接口。
 */
public interface MerchantNotifyTaskJpaRepository extends JpaRepository<MerchantNotifyTaskPO, Long> {

    Optional<MerchantNotifyTaskPO> findByTaskId(String taskId);

    List<MerchantNotifyTaskPO> findByStatusAndNextRetryTimeBefore(
            MerchantNotifyTask.NotifyStatus status, Instant time);
}
