package com.zxpay.application.port.out;

import com.zxpay.sharedkernel.event.DomainEvent;

import java.util.Collection;

/**
 * 出站端口：领域事件发布。
 *
 * <p><b>关键约束：事件必须在数据库事务提交之后发布。</b>
 *
 * <p>顺序反了会怎样？先发消息、后提交事务，
 * 一旦事务回滚，消息已经出去了。下游的清结算、商户通知、发货系统
 * 全都认为「支付成功」，而库里根本没有这笔——
 * 这是分布式系统里最经典的「幽灵消息」问题，且事后极难清理。
 *
 * <p>两种可靠实现：
 * <ol>
 *   <li><b>事务性发件箱（Transactional Outbox）</b>：事件与业务数据同库同事务写入
 *       {@code outbox} 表，再由独立的投递任务读取发送。
 *       优点是完全可靠，不依赖任何特殊中间件。生产推荐。</li>
 *   <li><b>事务提交后回调</b>：Spring 的
 *       {@code TransactionSynchronizationManager.registerSynchronization}
 *       在 {@code afterCommit} 中发布。实现简单，
 *       但进程在提交后、发送前崩溃会丢事件。</li>
 * </ol>
 *
 * <p>本 Demo 用第二种（日志打印 + 内存收集），
 * 生产请用第一种，并配 {@code eventId} 做消费端去重。
 */
public interface DomainEventPublisher {

    void publish(DomainEvent event);

    default void publishAll(Collection<? extends DomainEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        events.forEach(this::publish);
    }
}
