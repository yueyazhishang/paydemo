package com.demo.payment.infra.outbox;

import com.demo.payment.application.outbox.OutboxEvent;
import com.demo.payment.application.outbox.OutboxStore;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Outbox 存储的内存实现。
 *
 * <p><b>生产替换为 MySQL 表：</b>
 * <pre>
 *   CREATE TABLE outbox_event (
 *     event_id      VARCHAR(64) PRIMARY KEY,
 *     aggregate_id  VARCHAR(64) NOT NULL,
 *     event_type    VARCHAR(64) NOT NULL,
 *     payload       TEXT NOT NULL,
 *     status        TINYINT NOT NULL DEFAULT 0,
 *     retry_count   INT NOT NULL DEFAULT 0,
 *     created_at    DATETIME NOT NULL,
 *     sent_at       DATETIME,
 *     INDEX idx_status_created (status, created_at)
 *   );
 * </pre>
 *
 * <p>注意索引设计：{@code (status, created_at)} 是为了让投递任务
 * 能高效地"扫描待发送且最早的一批"。
 */
@org.springframework.stereotype.Repository
public class InMemoryOutboxStore implements OutboxStore {

    private final Map<String, OutboxEvent> store = new ConcurrentHashMap<>();

    @Override
    public void append(OutboxEvent event) {
        // 必须与业务数据在同一事务中（此处由外层 @Transactional 保证）
        store.put(event.eventId(), event);
    }

    @Override
    public List<OutboxEvent> fetchPending(int limit) {
        return store.values().stream()
                .filter(OutboxEvent::needsRetry)
                .sorted(java.util.Comparator.comparing(OutboxEvent::createdAt))
                .limit(limit)
                .collect(Collectors.toList());
    }

    @Override
    public void update(OutboxEvent event) {
        store.put(event.eventId(), event);
    }

    @Override
    public int cleanupSentOlderThan(int days) {
        Instant threshold = Instant.now().minus(days, ChronoUnit.DAYS);
        List<String> toRemove = store.values().stream()
                .filter(e -> e.status() == OutboxEvent.OutboxStatus.SENT
                        && e.sentAt() != null && e.sentAt().isBefore(threshold))
                .map(OutboxEvent::eventId)
                .toList();
        toRemove.forEach(store::remove);
        return toRemove.size();
    }
}
