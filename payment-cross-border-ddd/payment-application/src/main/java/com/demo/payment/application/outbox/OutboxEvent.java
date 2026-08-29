package com.demo.payment.application.outbox;

import java.time.Instant;

/**
 * Outbox 事件记录。
 *
 * <p><b>分布式事务的经典难题：</b>
 * "写数据库"和"发消息"是两个独立系统，无法放在同一个本地事务里。
 * 于是必然出现：
 * <ul>
 *   <li>先写库后发消息 → 消息发送失败，下游永远不知道订单已支付。</li>
 *   <li>先发消息后写库 → 库写入失败（事务回滚），下游却收到了"支付成功"，
 *       会造成发货但没收到钱的资损。</li>
 * </ul>
 *
 * <p><b>Outbox 模式是标准解法：</b>
 * 把要发的消息作为一行数据，<b>和业务数据在同一个本地事务里</b>写入 outbox 表。
 * 事务提交后，由独立的投递线程读取 outbox 发往 MQ，成功后标记已发送。
 * 这样"业务状态"与"消息"的原子性由数据库事务保证。
 *
 * <p>代价是消息可能重复投递（投递成功但标记失败，下次重投），
 * 因此<b>消费端必须做幂等</b> —— 这是 Outbox 模式的必要配套。
 */
public record OutboxEvent(
        String eventId,
        String aggregateType,
        String aggregateId,
        String eventType,
        String payload,
        OutboxStatus status,
        int retryCount,
        Instant createdAt,
        Instant sentAt,
        String lastError
) {

    public enum OutboxStatus {
        /** 待发送 */
        PENDING,
        /** 已发送 */
        SENT,
        /** 发送失败，等待重试 */
        FAILED,
        /** 超过最大重试次数，需人工介入 */
        DEAD
    }

    public static OutboxEvent pending(String eventId, String aggregateType, String aggregateId,
                                      String eventType, String payload) {
        return new OutboxEvent(eventId, aggregateType, aggregateId, eventType, payload,
                OutboxStatus.PENDING, 0, Instant.now(), null, null);
    }

    public OutboxEvent markSent() {
        return new OutboxEvent(eventId, aggregateType, aggregateId, eventType, payload,
                OutboxStatus.SENT, retryCount, createdAt, Instant.now(), null);
    }

    public OutboxEvent markFailed(String error) {
        boolean dead = retryCount + 1 >= 5;
        return new OutboxEvent(eventId, aggregateType, aggregateId, eventType, payload,
                dead ? OutboxStatus.DEAD : OutboxStatus.FAILED, retryCount + 1,
                createdAt, null, error);
    }

    public boolean needsRetry() {
        return status == OutboxStatus.PENDING || status == OutboxStatus.FAILED;
    }
}
