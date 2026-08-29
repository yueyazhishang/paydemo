package com.demo.payment.application.outbox;

import java.util.List;

/**
 * Outbox 存储端口。
 *
 * <p><b>关键约束：{@code append} 必须与业务数据在同一事务中执行。</b>
 * 实现时通常直接注入同一个 DataSource，由上层 {@code @Transactional} 保证。
 * 若 append 用了独立连接/独立事务，Outbox 就失去意义了。
 */
public interface OutboxStore {

    /** 追加事件（与业务写在同一事务内） */
    void append(OutboxEvent event);

    /** 批量拉取待发送事件 */
    List<OutboxEvent> fetchPending(int limit);

    /** 更新事件状态 */
    void update(OutboxEvent event);

    /** 清理已发送的历史数据（保留 N 天） */
    int cleanupSentOlderThan(int days);
}
