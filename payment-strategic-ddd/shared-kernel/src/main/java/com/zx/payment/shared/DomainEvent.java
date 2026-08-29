package com.zx.payment.shared;

import java.time.Instant;
import java.util.UUID;

/**
 * 领域事件基类：上下文【内部】发生的事实。
 *
 * 与集成事件（IntegrationEvent）的区别：
 *  - 领域事件：上下文内部的模型可以听，可以携带领域对象，随上下文演进自由修改；
 *  - 集成事件：跨上下文契约，只读、版本化、只带原始数据（String/long），不得携带领域对象。
 *
 * 三要素是领域事件的标配：
 *  - eventId：幂等去重与链路追踪的依据；
 *  - occurredAt：事实发生时间（不是处理时间）；
 *  - aggregateId：事件归属的聚合，便于按聚合重放。
 */
public abstract class DomainEvent {

    private final String eventId;
    private final Instant occurredAt;
    private final String aggregateId;

    protected DomainEvent(String aggregateId) {
        this.eventId = UUID.randomUUID().toString();
        this.occurredAt = Instant.now();
        this.aggregateId = aggregateId;
    }

    public String eventId() {
        return eventId;
    }

    public Instant occurredAt() {
        return occurredAt;
    }

    public String aggregateId() {
        return aggregateId;
    }
}
