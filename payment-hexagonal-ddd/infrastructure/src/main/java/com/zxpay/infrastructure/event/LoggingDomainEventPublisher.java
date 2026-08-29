package com.zxpay.infrastructure.event;

import com.zxpay.application.port.out.DomainEventPublisher;
import com.zxpay.sharedkernel.event.DomainEvent;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 领域事件发布器的演示实现：打印日志 + 内存留存。
 *
 * <p><b>生产实现必须换成事务性发件箱（Transactional Outbox）</b>，原因如下：
 *
 * <p>本实现是同步的、进程内的。若进程在「数据库事务已提交、事件尚未发出」的
 * 瞬间崩溃，事件就永久丢失了——下游的清结算、商户通知全都收不到，
 * 而这笔钱已经收了。
 *
 * <p>正确做法：事件与业务数据在<b>同一个数据库事务</b>里写入 {@code outbox} 表，
 * 再由独立的投递任务读取并发送，发送成功后标记已投递。
 * 这样「业务数据存在」与「事件一定会被发出」是原子的。
 *
 * <p>此外，消费端必须按 {@code eventId} 做去重：
 * 「至少一次投递」意味着同一事件可能被消费两次，
 * 去重能力要放在消费端，而不是指望发送端只发一次。
 */
@Component
public class LoggingDomainEventPublisher implements DomainEventPublisher {

    /** 已发布事件，仅供演示时查看。生产不要这样存——会内存溢出。 */
    private final List<DomainEvent> published = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void publish(DomainEvent event) {
        if (event == null) {
            return;
        }
        published.add(event);
        System.out.printf("[domain-event] type=%s eventId=%s aggregateId=%s occurredAt=%s%n",
                event.eventType(), event.eventId(), event.aggregateId(), event.occurredAt());
    }

    public List<DomainEvent> publishedEvents() {
        return List.copyOf(published);
    }
}
