package com.demo.payment.infra.outbox;

import com.demo.payment.application.outbox.OutboxEvent;
import com.demo.payment.application.outbox.OutboxStore;

import java.util.List;

/**
 * Outbox 投递任务。
 *
 * <p>独立线程/定时任务，负责把 outbox 表中的事件发往 MQ。
 *
 * <p><b>三个必须注意的点：</b>
 * <ol>
 *   <li><b>至少一次投递</b>：投递成功但标记失败会导致重投，
 *       因此消费端<b>必须幂等</b>。这是 Outbox 模式的代价与前提。</li>
 *   <li><b>拉取后要加锁或按状态更新</b>：多实例部署时，
 *       多个节点同时扫描会拉取到同一批事件。
 *       常用做法：{@code UPDATE ... SET status=SENDING WHERE status=PENDING LIMIT N}
 *       用 UPDATE 的行锁抢占。</li>
 *   <li><b>死信处理</b>：超过重试次数转入 DEAD 状态并告警，
 *       不能无限重试 —— 那会掩盖真实故障。</li>
 * </ol>
 */
@org.springframework.stereotype.Component
public class OutboxPublisherJob {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(OutboxPublisherJob.class);
    private static final int BATCH_SIZE = 100;

    private final OutboxStore outboxStore;

    public OutboxPublisherJob(OutboxStore outboxStore) {
        this.outboxStore = outboxStore;
    }

    /** 定时执行（真实环境用 @Scheduled(fixedDelay = 1000) 或 XXL-Job） */
    public void run() {
        List<OutboxEvent> events = outboxStore.fetchPending(BATCH_SIZE);
        for (OutboxEvent event : events) {
            try {
                // TODO 生产实现：发往 Kafka / RocketMQ
                //   kafkaTemplate.send("payment.domain.event", event.aggregateId(), event.payload());
                send(event);
                outboxStore.update(event.markSent());
            } catch (Exception e) {
                log.error("Outbox 事件投递失败 eventId={} retry={}", event.eventId(), event.retryCount(), e);
                outboxStore.update(event.markFailed(e.getMessage()));
            }
        }
    }

    private void send(OutboxEvent event) {
        log.info("[Outbox→MQ] topic=payment.domain.event key={} type={}",
                event.aggregateId(), event.eventType());
    }
}
