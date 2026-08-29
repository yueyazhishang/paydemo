package com.example.payment.domain.payment.event;

import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * 领域事件基类。聚合根内登记，应用层事务提交后发布。
 */
@Getter
public abstract class DomainEvent {

    private final String eventId;
    private final Instant occurredAt;

    protected DomainEvent() {
        this.eventId = UUID.randomUUID().toString();
        this.occurredAt = Instant.now();
    }
}
