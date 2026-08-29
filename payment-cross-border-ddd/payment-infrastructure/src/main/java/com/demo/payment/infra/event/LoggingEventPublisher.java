package com.demo.payment.infra.event;

import com.demo.payment.shared.event.DomainEvent;
import com.demo.payment.shared.event.EventPublisher;

import java.util.List;

/**
 * 事件发布实现（演示版：打印日志）。
 *
 * <p><b>生产环境应替换为 Outbox + MQ：</b>
 * <pre>
 *   publish(event) → outboxStore.append(...)
 *                      ↓ 独立线程
 *                  Kafka/RocketMQ 投递
 * </pre>
 *
 * <p><b>绝不可以在这里直接发 MQ。</b>
 * 直接发的后果：事务回滚了但消息已投递，下游收到"支付成功"
 * 而库里根本没有这笔单 —— 这是最严重的一类数据不一致。
 */
@org.springframework.stereotype.Component
public class LoggingEventPublisher implements EventPublisher {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(LoggingEventPublisher.class);

    @Override
    public void publish(DomainEvent event) {
        log.info("[DomainEvent] type={} aggregateId={} occurredAt={}",
                event.getClass().getSimpleName(), event.aggregateId(), event.occurredAt());
        // TODO 生产实现：写入 outbox 表，由投递任务发往 MQ
    }

    @Override
    public void publishAll(List<DomainEvent> events) {
        events.forEach(this::publish);
    }
}
