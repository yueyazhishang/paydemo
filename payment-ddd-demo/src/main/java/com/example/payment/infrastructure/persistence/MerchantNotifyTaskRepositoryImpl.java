package com.example.payment.infrastructure.persistence;

import com.example.payment.domain.payment.model.MerchantNotifyTask;
import com.example.payment.domain.payment.repository.MerchantNotifyTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 上游通知任务仓储端口实现。
 */
@Repository
@RequiredArgsConstructor
public class MerchantNotifyTaskRepositoryImpl implements MerchantNotifyTaskRepository {

    private final MerchantNotifyTaskJpaRepository jpaRepository;

    @Override
    public MerchantNotifyTask save(MerchantNotifyTask task) {
        MerchantNotifyTaskPO po = jpaRepository.findByTaskId(task.getTaskId())
                .orElseGet(() -> {
                    MerchantNotifyTaskPO newPO = new MerchantNotifyTaskPO();
                    newPO.setTaskId(task.getTaskId());
                    newPO.setRelatedTradeNo(task.getRelatedTradeNo());
                    newPO.setEventType(task.getEventType());
                    newPO.setNotifyUrl(task.getNotifyUrl());
                    newPO.setPayload(task.getPayload());
                    newPO.setCreatedAt(task.getCreatedAt());
                    return newPO;
                });
        po.setStatus(task.getStatus());
        po.setRetryCount(task.getRetryCount());
        po.setNextRetryTime(task.getNextRetryTime());
        po.setLastErrorMessage(task.getLastErrorMessage());
        jpaRepository.save(po);
        return task;
    }

    @Override
    public Optional<MerchantNotifyTask> findByTaskId(String taskId) {
        return jpaRepository.findByTaskId(taskId).map(this::toDomain);
    }

    @Override
    public List<MerchantNotifyTask> findDueTasks(Instant now) {
        return jpaRepository
                .findByStatusAndNextRetryTimeBefore(MerchantNotifyTask.NotifyStatus.WAITING, now)
                .stream().map(this::toDomain).toList();
    }

    private MerchantNotifyTask toDomain(MerchantNotifyTaskPO po) {
        return MerchantNotifyTask.rehydrate(
                po.getTaskId(), po.getRelatedTradeNo(), po.getEventType(),
                po.getNotifyUrl(), po.getPayload(), po.getStatus(),
                po.getRetryCount(), po.getNextRetryTime(),
                po.getLastErrorMessage(), po.getCreatedAt());
    }
}
