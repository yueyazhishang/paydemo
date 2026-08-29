package com.example.payment.domain.payment.repository;

import com.example.payment.domain.payment.model.MerchantNotifyTask;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 上游通知任务仓储接口。
 */
public interface MerchantNotifyTaskRepository {

    MerchantNotifyTask save(MerchantNotifyTask task);

    Optional<MerchantNotifyTask> findByTaskId(String taskId);

    /** 重试调度扫描：到期待通知（含重试）的任务 */
    List<MerchantNotifyTask> findDueTasks(Instant now);
}
