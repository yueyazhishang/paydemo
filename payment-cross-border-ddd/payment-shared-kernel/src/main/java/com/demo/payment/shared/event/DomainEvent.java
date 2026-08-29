package com.demo.payment.shared.event;

import java.time.Instant;

/**
 * 领域事件标记接口。
 *
 * <p>领域事件表达的是"领域中已经发生的事实"，用过去式命名（PaymentSucceeded 而非 PaySuccess）。
 * 它有三个用途：
 * <ol>
 *   <li><b>解耦限界上下文</b>：支付成功后要通知结算、账务、风控、营销，
 *       若用同步调用，支付主链路会被这些下游拖垮。改为事件驱动后，
 *       支付只负责"宣布事实"，谁关心谁订阅。</li>
 *   <li><b>审计溯源</b>：事件流就是完整的资金流水时间线，出问题能回放。</li>
 *   <li><b>可靠异步</b>：配合 Outbox 模式，保证"状态变更"与"事件发布"原子性。</li>
 * </ol>
 */
public interface DomainEvent {

    /** 事件发生时间 */
    Instant occurredAt();

    /** 事件唯一标识，用于消费端幂等去重 */
    default String eventId() {
        return java.util.UUID.randomUUID().toString();
    }

    /** 聚合根 ID，用于分区与追踪 */
    String aggregateId();
}
