package com.example.payment.domain.payment.model;

import lombok.Getter;

import java.time.Instant;

/**
 * 上游通知任务实体：支付/退款终态后异步通知业务方，失败按指数退避重试。
 * 独立于支付聚合持久化——通知是「结果分发」关注点，不应拖慢支付主流程。
 */
@Getter
public class MerchantNotifyTask {

    public enum NotifyStatus {
        /** 待通知/待重试 */
        WAITING,
        /** 通知成功（终态） */
        SUCCESS,
        /** 重试耗尽（终态，需人工/对账介入） */
        EXHAUSTED
    }

    /** 重试退避间隔（秒）：1min → 5min → 15min → 30min → 60min，共 1+N 次 */
    public static final long[] RETRY_BACKOFF_SECONDS = {60, 300, 900, 1800, 3600};

    private String taskId;

    /** 关联我方单号（支付单号或退款单号） */
    private String relatedTradeNo;

    /** 事件类型：PAYMENT_SUCCEEDED / REFUND_SUCCEEDED / PAYMENT_CLOSED ... */
    private String eventType;

    private String notifyUrl;

    /** 通知报文（JSON） */
    private String payload;

    private NotifyStatus status;

    private int retryCount;

    private Instant nextRetryTime;

    private String lastErrorMessage;

    private Instant createdAt;

    public static MerchantNotifyTask create(String relatedTradeNo, String eventType,
                                            String notifyUrl, String payload) {
        MerchantNotifyTask task = new MerchantNotifyTask();
        task.taskId = "NT" + java.util.UUID.randomUUID().toString().replace("-", "").toUpperCase();
        task.relatedTradeNo = relatedTradeNo;
        task.eventType = eventType;
        task.notifyUrl = notifyUrl;
        task.payload = payload;
        task.status = NotifyStatus.WAITING;
        task.retryCount = 0;
        task.nextRetryTime = Instant.now();
        task.createdAt = Instant.now();
        return task;
    }

    /** 由持久化层重建任务（保持 retryCount/nextRetryTime 原值） */
    public static MerchantNotifyTask rehydrate(String taskId, String relatedTradeNo, String eventType,
                                               String notifyUrl, String payload, NotifyStatus status,
                                               int retryCount, Instant nextRetryTime,
                                               String lastErrorMessage, Instant createdAt) {
        MerchantNotifyTask task = new MerchantNotifyTask();
        task.taskId = taskId;
        task.relatedTradeNo = relatedTradeNo;
        task.eventType = eventType;
        task.notifyUrl = notifyUrl;
        task.payload = payload;
        task.status = status;
        task.retryCount = retryCount;
        task.nextRetryTime = nextRetryTime;
        task.lastErrorMessage = lastErrorMessage;
        task.createdAt = createdAt;
        return task;
    }

    /** 是否可执行（首次或退避到期） */
    public boolean isDue(Instant now) {
        return status == NotifyStatus.WAITING && !nextRetryTime.isAfter(now);
    }

    /** 通知成功 */
    public void markSuccess() {
        this.status = NotifyStatus.SUCCESS;
        this.lastErrorMessage = null;
    }

    /** 通知失败：安排退避重试；超过上限则 EXHAUSTED */
    public void markFailed(String errorMessage) {
        if (retryCount >= RETRY_BACKOFF_SECONDS.length) {
            this.status = NotifyStatus.EXHAUSTED;
            this.lastErrorMessage = errorMessage;
            return;
        }
        long backoff = RETRY_BACKOFF_SECONDS[retryCount];
        this.retryCount++;
        this.nextRetryTime = Instant.now().plusSeconds(backoff);
        this.lastErrorMessage = errorMessage;
    }
}
