package com.example.payment.infrastructure.persistence;

import com.example.payment.domain.payment.model.MerchantNotifyTask;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 上游通知任务 PO。
 */
@Getter
@Setter
@Entity
@Table(name = "merchant_notify_task")
public class MerchantNotifyTaskPO {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false, unique = true, length = 64)
    private String taskId;

    @Column(name = "related_trade_no", nullable = false, length = 64)
    private String relatedTradeNo;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "notify_url", nullable = false, length = 512)
    private String notifyUrl;

    @Column(name = "payload", columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private MerchantNotifyTask.NotifyStatus status;

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount;

    @Column(name = "next_retry_time", nullable = false)
    private Instant nextRetryTime;

    @Column(name = "last_error_message", length = 512)
    private String lastErrorMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
