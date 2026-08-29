package com.zxpay.sharedkernel.event;

import java.time.Instant;
import java.util.UUID;

/**
 * 领域事件。
 *
 * <p>在支付系统里，事件不是可选的「锦上添花」，而是解耦核心链路与旁路的唯一手段：
 * 风控、清结算、商户通知、BI、对账，全都通过订阅事件接入。如果把这些逻辑直接写在
 * 支付主流程里，主流程会被拖垮，且任何一处改动都要动核心代码。
 *
 * <p>约定：
 * <ul>
 *   <li>事件命名统一用过去式（{@code PaymentSucceeded}，不是 {@code PaymentSuccess}）。</li>
 *   <li>事件是<b>事实</b>，不是命令，因此字段应当只读且自包含（消费方不需要回查就能处理）。</li>
 *   <li>{@code aggregateId} 是字符串而非类型化 ID，因为事件会跨上下文、跨服务传播，
 *       出网后不该再依赖领域类型。</li>
 * </ul>
 */
public interface DomainEvent {

    /** 事件唯一标识，用于消费端幂等去重。 */
    String eventId();

    /** 事件发生时间（UTC 毫秒时间戳）。 */
    Instant occurredAt();

    /** 产生事件的聚合标识。 */
    String aggregateId();

    /** 事件类型，用于 MQ 的 routing key / 事件表索引。 */
    default String eventType() {
        return getClass().getSimpleName();
    }

    static String newEventId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
