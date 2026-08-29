package com.example.payment.infrastructure.schedule;

import com.example.payment.application.service.UpstreamNotifyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 上游通知重试定时任务：扫描到期的 WAITING 任务并重试。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotifyRetryJob {

    private final UpstreamNotifyService upstreamNotifyService;

    /** 每 30 秒扫描一次到期任务 */
    @Scheduled(fixedDelay = 30_000, initialDelay = 15_000)
    public void retryDueNotifications() {
        try {
            upstreamNotifyService.retryPendingTasks();
        } catch (Exception e) {
            log.error("上游通知重试调度异常", e);
        }
    }
}
