package com.demo.payment.application.outbox;

import com.demo.payment.shared.event.DomainEvent;

import java.util.List;

/**
 * Outbox 服务 —— 领域事件与消息投递之间的桥梁。
 *
 * <p>领域层产生的事件不会直接发往 MQ，而是：
 * <pre>
 *   聚合变更 → 产生 DomainEvent → 转 OutboxEvent → 同事务写入 outbox 表
 *                                                      ↓（事务提交后）
 *                                     独立线程拉取 → 发 MQ → 标记 SENT
 * </pre>
 *
 * <p><b>为什么不让领域事件直接进 Outbox？</b>
 * 因为领域事件是内存对象，带有业务语义；Outbox 事件是持久化记录，
 * 需要序列化、重试计数、死信标记等技术属性。两者的生命周期不同，应当分离。
 */
public class OutboxService {

    private final OutboxStore store;
    private final EventSerializer serializer;

    public OutboxService(OutboxStore store, EventSerializer serializer) {
        this.store = store;
        this.serializer = serializer;
    }

    /** 将聚合产生的领域事件写入 Outbox */
    public void capture(String aggregateType, String aggregateId, List<DomainEvent> events) {
        for (DomainEvent event : events) {
            OutboxEvent outbox = OutboxEvent.pending(
                    event.eventId(),
                    aggregateType,
                    aggregateId,
                    event.getClass().getSimpleName(),
                    serializer.serialize(event)
            );
            store.append(outbox);
        }
    }

    /** 事件序列化抽象（JSON / Avro / Protobuf） */
    public interface EventSerializer {
        String serialize(DomainEvent event);
    }
}
