package com.demo.payment.shared.event;

/**
 * 事件发布端口（domain 层定义，infrastructure 层实现）。
 * 依赖倒置：领域层定义"我要发事件"，但不关心是 Kafka 还是内存队列。
 */
public interface EventPublisher {

    /**
     * 发布单个事件。
     *
     * <p><b>注意：真实实现必须走 Outbox 模式</b>，即事件先随业务事务写入本地 outbox 表，
     * 再由独立的投递线程发往 MQ。直接在这里发 MQ 会产生经典问题：
     * 事务回滚了但消息已发出，下游收到"支付成功"而库里根本没有这笔单。
     */
    void publish(DomainEvent event);

    void publishAll(java.util.List<DomainEvent> events);
}
