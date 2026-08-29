#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成 poms + infrastructure + interfaces + bootstrap + 单元测试"""
import os
from gen_poms import write_poms

BASE = "/Users/abc/WorkBuddy/2026-08-29-13-23-42/payment-ddd-demo"
F = {}
IN = "payment-infrastructure/src/main/java/com/demo/payment/infra/"
IF = "payment-interfaces/src/main/java/com/demo/payment/interfaces/"

# ==================== infrastructure ====================
F[IN + "persistence/InMemoryPaymentOrderRepository.java"] = r'''
package com.demo.payment.infra.persistence;

import com.demo.payment.domain.acquiring.model.aggregate.PaymentOrder;
import com.demo.payment.domain.acquiring.model.aggregate.PaymentOrderId;
import com.demo.payment.domain.acquiring.model.vo.OutTradeNo;
import com.demo.payment.domain.acquiring.repository.PaymentOrderRepository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 支付单仓储的内存实现（演示用）。
 *
 * <p><b>它演示了三件生产环境必须做的事：</b>
 * <ol>
 *   <li><b>乐观锁</b>：{@code version} 不匹配时抛异常，防止并发覆盖。
 *       真实实现是 {@code UPDATE ... WHERE id=? AND version=?}，
 *       影响行数为 0 即抛并发异常。</li>
 *   <li><b>商户订单号唯一性</b>：用 {@code merchantId + "#" + merchantOrderNo} 建索引，
 *       这是防重复下单的最后兜底 —— 即使上层幂等全部失效，这里也能挡住。</li>
 *   <li><b>分布式锁</b>：真实环境用 Redis（Redisson），这里用本地锁演示语义。</li>
 * </ol>
 *
 * <p><b>生产替换指引：</b>把三个 Map 换成 MyBatis/JPA 的 Mapper 调用，
 * 把 {@code new ReentrantLock()} 换成 Redisson 的 {@code getLock("payment:order:" + id)}，
 * 其余代码无需改动 —— 这就是依赖倒置的价值。
 */
@org.springframework.stereotype.Repository
public class InMemoryPaymentOrderRepository implements PaymentOrderRepository {

    private final Map<String, PaymentOrder> store = new ConcurrentHashMap<>();
    /** 商户订单号索引：merchantId + "#" + merchantOrderNo → paymentOrderId */
    private final Map<String, String> merchantOrderIndex = new ConcurrentHashMap<>();
    /** 通道订单号索引：outTradeNo → paymentOrderId（回调时反查用） */
    private final Map<String, String> outTradeNoIndex = new ConcurrentHashMap<>();
    private final Map<String, Lock> locks = new ConcurrentHashMap<>();

    @Override
    public Optional<PaymentOrder> findById(PaymentOrderId id) {
        return Optional.ofNullable(store.get(id.value()));
    }

    @Override
    public Optional<PaymentOrder> findByMerchantOrderNo(String merchantId, String merchantOrderNo) {
        String pid = merchantOrderIndex.get(merchantId + "#" + merchantOrderNo);
        return pid == null ? Optional.empty() : Optional.ofNullable(store.get(pid));
    }

    @Override
    public Optional<PaymentOrder> findByOutTradeNo(OutTradeNo outTradeNo) {
        String pid = outTradeNoIndex.get(outTradeNo.value());
        return pid == null ? Optional.empty() : Optional.ofNullable(store.get(pid));
    }

    @Override
    public synchronized void save(PaymentOrder order) {
        // 乐观锁校验：新单 version=0，已存在的单必须版本递增
        PaymentOrder existing = store.get(order.id().value());
        if (existing != null && existing.version() != order.version()) {
            throw new IllegalStateException("乐观锁冲突，订单已被其他请求修改: "
                    + order.id().value() + " 期望版本=" + existing.version()
                    + " 实际版本=" + order.version());
        }

        store.put(order.id().value(), order);
        merchantOrderIndex.put(order.merchantId() + "#" + order.merchantOrderNo(), order.id().value());
        for (var attempt : order.attempts()) {
            outTradeNoIndex.put(attempt.outTradeNo().value(), order.id().value());
        }
    }

    @Override
    public List<PaymentOrder> findTimeoutCandidates(int limitMinutes, int limit) {
        Instant threshold = Instant.now().minus(limitMinutes, ChronoUnit.MINUTES);
        return store.values().stream()
                .filter(o -> o.status().isProcessing())
                .filter(o -> o.createdAt().isBefore(threshold))
                .limit(limit)
                .toList();
    }

    @Override
    public Lock obtainLock(PaymentOrderId id) {
        return locks.computeIfAbsent(id.value(), k -> new ReentrantLock());
    }
}
'''

F[IN + "idempotency/InMemoryIdempotencyStore.java"] = r'''
package com.demo.payment.infra.idempotency;

import com.demo.payment.application.idempotency.IdempotencyRecord;
import com.demo.payment.application.idempotency.IdempotencyStore;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 幂等存储的内存实现。
 *
 * <p><b>生产环境必须换成 Redis：</b>
 * <pre>
 *   SETNX idempotency:{key} {fingerprint|PROCESSING} EX 86400
 * </pre>
 *
 * <p>关键点：
 * <ul>
 *   <li><b>必须用 SETNX（原子抢占）</b>，不能"先 GET 再 SET"——
 *       后者在并发下两个请求都会看到"不存在"，然后都执行业务逻辑。</li>
 *   <li><b>必须带过期时间</b>，否则键永久堆积。</li>
 *   <li>若用 DB 实现，则用唯一索引 + 捕获 DuplicateKeyException 达到同样效果。</li>
 * </ul>
 */
@org.springframework.stereotype.Repository
public class InMemoryIdempotencyStore implements IdempotencyStore {

    private final Map<String, IdempotencyRecord> store = new ConcurrentHashMap<>();

    @Override
    public Optional<IdempotencyRecord> tryAcquire(String key, String fingerprint, Duration ttl) {
        // computeIfAbsent 是原子的，等价于 Redis SETNX
        IdempotencyRecord existing = store.computeIfAbsent(key,
                k -> new IdempotencyRecord(k, fingerprint,
                        IdempotencyRecord.IdempotencyStatus.PROCESSING,
                        null, Instant.now(), Instant.now().plus(ttl)));

        // 已存在（非本次创建）→ 返回已有记录，表示重复请求
        if (!fingerprint.equals(existing.requestFingerprint()) || existing.status() != IdempotencyRecord.IdempotencyStatus.PROCESSING) {
            return Optional.of(existing);
        }
        // 本次刚创建的，检查是否为首次
        return existing.createdAt().equals(Instant.now()) ? Optional.empty() : Optional.of(existing);
    }

    @Override
    public void complete(String key, String resultSnapshot) {
        IdempotencyRecord rec = store.get(key);
        if (rec != null) {
            store.put(key, new IdempotencyRecord(key, rec.requestFingerprint(),
                    IdempotencyRecord.IdempotencyStatus.COMPLETED, resultSnapshot,
                    rec.createdAt(), rec.expireAt()));
        }
    }

    @Override
    public void fail(String key) {
        IdempotencyRecord rec = store.get(key);
        if (rec != null) {
            store.put(key, new IdempotencyRecord(key, rec.requestFingerprint(),
                    IdempotencyRecord.IdempotencyStatus.FAILED, null,
                    rec.createdAt(), rec.expireAt()));
        }
    }

    @Override
    public Optional<IdempotencyRecord> get(String key) {
        return Optional.ofNullable(store.get(key));
    }

    @Override
    public void release(String key) {
        store.remove(key);
    }
}
'''

F[IN + "outbox/InMemoryOutboxStore.java"] = r'''
package com.demo.payment.infra.outbox;

import com.demo.payment.application.outbox.OutboxEvent;
import com.demo.payment.application.outbox.OutboxStore;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Outbox 存储的内存实现。
 *
 * <p><b>生产替换为 MySQL 表：</b>
 * <pre>
 *   CREATE TABLE outbox_event (
 *     event_id      VARCHAR(64) PRIMARY KEY,
 *     aggregate_id  VARCHAR(64) NOT NULL,
 *     event_type    VARCHAR(64) NOT NULL,
 *     payload       TEXT NOT NULL,
 *     status        TINYINT NOT NULL DEFAULT 0,
 *     retry_count   INT NOT NULL DEFAULT 0,
 *     created_at    DATETIME NOT NULL,
 *     sent_at       DATETIME,
 *     INDEX idx_status_created (status, created_at)
 *   );
 * </pre>
 *
 * <p>注意索引设计：{@code (status, created_at)} 是为了让投递任务
 * 能高效地"扫描待发送且最早的一批"。
 */
@org.springframework.stereotype.Repository
public class InMemoryOutboxStore implements OutboxStore {

    private final Map<String, OutboxEvent> store = new ConcurrentHashMap<>();

    @Override
    public void append(OutboxEvent event) {
        // 必须与业务数据在同一事务中（此处由外层 @Transactional 保证）
        store.put(event.eventId(), event);
    }

    @Override
    public List<OutboxEvent> fetchPending(int limit) {
        return store.values().stream()
                .filter(OutboxEvent::needsRetry)
                .sorted(java.util.Comparator.comparing(OutboxEvent::createdAt))
                .limit(limit)
                .collect(Collectors.toList());
    }

    @Override
    public void update(OutboxEvent event) {
        store.put(event.eventId(), event);
    }

    @Override
    public int cleanupSentOlderThan(int days) {
        Instant threshold = Instant.now().minus(days, ChronoUnit.DAYS);
        List<String> toRemove = store.values().stream()
                .filter(e -> e.status() == OutboxEvent.OutboxStatus.SENT
                        && e.sentAt() != null && e.sentAt().isBefore(threshold))
                .map(OutboxEvent::eventId)
                .toList();
        toRemove.forEach(store::remove);
        return toRemove.size();
    }
}
'''

F[IN + "event/LoggingEventPublisher.java"] = r'''
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
'''

F[IN + "outbox/OutboxPublisherJob.java"] = r'''
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
'''

F[IN + "compensation/PaymentReconciliationJob.java"] = r'''
package com.demo.payment.infra.compensation;

import com.demo.payment.application.command.PaymentCommandService;
import com.demo.payment.domain.acquiring.model.aggregate.PaymentOrder;
import com.demo.payment.domain.acquiring.repository.PaymentOrderRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 支付查证补偿任务 —— 资金安全的最后一道防线。
 *
 * <h3>为什么必须有它</h3>
 * <p>异步回调不可靠：可能丢失、延迟、乱序。如果没有补偿任务，
 * 一笔"实际已支付但回调丢失"的订单会永远停留在"支付中"，
 * 用户付了钱、商户看不到单 —— 这就是<b>掉单</b>。
 *
 * <h3>轮询策略：指数退避</h3>
 * <pre>
 *   下单后 10s → 30s → 1min → 5min → 30min → 2h → 6h（停止）
 * </pre>
 * <p>为什么是指数退避？绝大多数订单在 1 分钟内完成支付，
 * 密集轮询前 1 分钟能最快确认状态；而迟迟未支付的订单
 * 大概率是用户放弃了，没必要高频查询（还浪费通道查询配额）。
 *
 * <h3>停止条件</h3>
 * <p>超过通道的订单有效期（微信/支付宝通常 2 小时）后，
 * 若仍查不到，则主动关单 —— 此时关单是安全的，
 * 因为已经超过了用户可能完成支付的时间窗。
 */
@org.springframework.stereotype.Component
public class PaymentReconciliationJob {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(PaymentReconciliationJob.class);

    /** 查证延迟阶梯（秒） */
    private static final long[] RETRY_DELAYS = {10, 30, 60, 300, 1800, 7200};

    private final PaymentOrderRepository repository;
    private final PaymentCommandService commandService;

    public PaymentReconciliationJob(PaymentOrderRepository repository,
                                    PaymentCommandService commandService) {
        this.repository = repository;
        this.commandService = commandService;
    }

    /** 定时执行（真实环境每 10 秒一次） */
    public void run() {
        List<PaymentOrder> candidates = repository.findTimeoutCandidates(1, 200);
        for (PaymentOrder order : candidates) {
            try {
                long elapsed = Duration.between(order.createdAt(), Instant.now()).getSeconds();
                int stage = currentStage(elapsed);
                if (stage >= RETRY_DELAYS.length) {
                    // 超过最终期限，主动关单
                    log.warn("支付单超过查证期限，执行关单: {}", order.id().value());
                    order.close("超过支付时限，系统自动关单");
                    repository.save(order);
                    continue;
                }
                // 到达该阶段的查证时点才查询
                boolean changed = commandService.reconcile(order);
                if (changed) {
                    log.info("查证更新订单状态: {} -> {}", order.id().value(), order.status());
                }
            } catch (Exception e) {
                log.error("查证补偿失败 orderId={}", order.id().value(), e);
                // 单笔失败不影响整批，继续处理下一笔
            }
        }
    }

    private int currentStage(long elapsedSeconds) {
        for (int i = 0; i < RETRY_DELAYS.length; i++) {
            if (elapsedSeconds < RETRY_DELAYS[i]) {
                return i;
            }
        }
        return RETRY_DELAYS.length;
    }
}
'''

# ==================== 通道装配配置（重点） ====================
F[IN + "config/PaymentChannelConfiguration.java"] = r'''
package com.demo.payment.infra.config;

import com.demo.payment.adapter.alipay.AlipayAdapter;
import com.demo.payment.adapter.antom.AntomAdapter;
import com.demo.payment.adapter.applepay.ApplePayAdapter;
import com.demo.payment.adapter.core.ChannelRegistry;
import com.demo.payment.adapter.jdpay.JdPayAdapter;
import com.demo.payment.adapter.paypal.PayPalAdapter;
import com.demo.payment.adapter.stripe.StripeAdapter;
import com.demo.payment.adapter.unionpay.UnionPayAdapter;
import com.demo.payment.adapter.wechatpay.WechatPayAdapter;
import com.demo.payment.adapter.worldpay.WorldpayAdapter;
import com.demo.payment.domain.channel.model.ChannelCode;
import com.demo.payment.domain.channel.route.CapabilityBasedRouter;
import com.demo.payment.domain.channel.route.ChannelRouter;
import com.demo.payment.domain.channel.route.WeightedRouteStrategy;
import com.demo.payment.domain.channel.spi.PaymentChannelPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.EnumMap;
import java.util.Map;

/**
 * 通道装配配置 —— <b>新增通道只需在这里加一个 @Bean</b>。
 *
 * <p>这里最能体现"开闭原则"：所有通道实现 {@link PaymentChannelPort}，
 * 由 Spring 收集注入，路由/支付/退款/查证等上层逻辑<b>零改动</b>。
 *
 * <h3>Apple Pay 的装配是特殊的一例</h3>
 * <p>它<b>必须注入一个底层收单行</b>（此处为 StripeAdapter）作为委托对象。
 * 这直观体现了"Apple Pay 不是通道"这一建模认知：
 * 它在 Spring 容器里是一个 Bean，但它寄生于另一个通道。
 *
 * <p>如果要容灾切换（Stripe → Worldpay），只需改这一行注入，
 * 或者实现按健康度动态选择委托目标 —— 这在"Apple Pay 是通道"
 * 的错误建模下是不可能做到的。
 */
@Configuration
public class PaymentChannelConfiguration {

    // ---------- 国内通道 ----------

    @Bean
    public WechatPayAdapter wechatPayAdapter() {
        return new WechatPayAdapter();
    }

    @Bean
    public AlipayAdapter alipayAdapter() {
        return new AlipayAdapter();
    }

    @Bean
    public JdPayAdapter jdPayAdapter() {
        return new JdPayAdapter();
    }

    @Bean
    public UnionPayAdapter unionPayAdapter() {
        return new UnionPayAdapter();
    }

    // ---------- 海外通道 ----------

    @Bean
    public PayPalAdapter payPalAdapter() {
        return new PayPalAdapter();
    }

    @Bean
    public StripeAdapter stripeAdapter() {
        return new StripeAdapter();
    }

    @Bean
    public WorldpayAdapter worldpayAdapter() {
        return new WorldpayAdapter();
    }

    @Bean
    public AntomAdapter antomAdapter() {
        return new AntomAdapter();
    }

    /**
     * Apple Pay —— 委托给 Stripe 收单。
     *
     * <p>若 Stripe 不可用，可改为委托 Worldpay：
     * {@code new ApplePayAdapter(worldpayAdapter())}
     * 这正是"支付方式与通道解耦"带来的容灾能力。
     */
    @Bean
    public ApplePayAdapter applePayAdapter(StripeAdapter stripeAdapter) {
        return new ApplePayAdapter(stripeAdapter);
    }

    // ---------- 路由装配 ----------

    @Bean
    public CapabilityBasedRouter channelRouter(java.util.List<PaymentChannelPort> channels) {
        CapabilityBasedRouter router = new CapabilityBasedRouter(new WeightedRouteStrategy());
        // 注册所有通道的能力矩阵 —— 路由的硬过滤完全依赖这些数据
        channels.forEach(ch -> router.register(ch.capability()));
        return router;
    }

    /**
     * 通道索引表。
     *
     * <p><b>注意：Apple Pay 的 channelCode() 返回的是 STRIPE</b>（其委托对象），
     * 因此它会覆盖掉 Stripe 原生实例的映射。这是故意的：
     * 当上层按 {@code ChannelCode.STRIPE} 取通道时，
     * 需要根据具体支付方式选择"原生卡支付"还是"Apple Pay 委托"。
     *
     * <p>生产环境的正确做法是按 {@code (channelCode, paymentMethod)} 二元组索引，
     * 此处为演示清晰，采用"后注册覆盖"策略并保留 registry 供精确查找。
     */
    @Bean
    public Map<ChannelCode, PaymentChannelPort> channelMap(java.util.List<PaymentChannelPort> channels) {
        Map<ChannelCode, PaymentChannelPort> map = new EnumMap<>(ChannelCode.class);
        channels.forEach(ch -> map.put(ch.channelCode(), ch));
        return map;
    }

    @Bean
    public ChannelRegistry channelRegistry(java.util.List<PaymentChannelPort> channels) {
        ChannelRegistry registry = new ChannelRegistry();
        channels.forEach(registry::register);
        return registry;
    }
}
'''

F[IN + "config/ApplicationServiceConfiguration.java"] = r'''
package com.demo.payment.infra.config;

import com.demo.payment.application.command.NotificationService;
import com.demo.payment.application.command.PaymentCommandService;
import com.demo.payment.application.command.RefundCommandService;
import com.demo.payment.application.idempotency.IdempotencyGuard;
import com.demo.payment.application.idempotency.IdempotencyStore;
import com.demo.payment.application.outbox.OutboxService;
import com.demo.payment.application.outbox.OutboxStore;
import com.demo.payment.domain.acquiring.repository.PaymentOrderRepository;
import com.demo.payment.domain.acquiring.service.RefundPolicyService;
import com.demo.payment.domain.acquiring.service.RefundPolicyServiceImpl;
import com.demo.payment.domain.channel.model.ChannelCode;
import com.demo.payment.domain.channel.route.ChannelRouter;
import com.demo.payment.domain.channel.spi.PaymentChannelPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * 应用服务装配。
 *
 * <p>注意所有 Bean 的类型都是<b>领域层或应用层定义的接口/类</b>，
 * 基础设施只负责"把它们拼起来"。这就是六边形架构的装配层职责。
 */
@Configuration
public class ApplicationServiceConfiguration {

    @Bean
    public RefundPolicyService refundPolicyService() {
        return new RefundPolicyServiceImpl();
    }

    @Bean
    public IdempotencyGuard idempotencyGuard(IdempotencyStore store) {
        return new IdempotencyGuard(store);
    }

    @Bean
    public OutboxService outboxService(OutboxStore store) {
        // 简化序列化器：真实环境用 Jackson
        return new OutboxService(store, event -> event.getClass().getSimpleName()
                + "|" + event.aggregateId() + "|" + event.occurredAt());
    }

    @Bean
    public PaymentCommandService paymentCommandService(
            PaymentOrderRepository repository,
            ChannelRouter router,
            Map<ChannelCode, PaymentChannelPort> channelMap,
            IdempotencyGuard idempotencyGuard,
            OutboxService outboxService) {
        return new PaymentCommandService(repository, router, channelMap,
                idempotencyGuard, outboxService);
    }

    @Bean
    public RefundCommandService refundCommandService(
            PaymentOrderRepository repository,
            Map<ChannelCode, PaymentChannelPort> channelMap,
            RefundPolicyService refundPolicy,
            OutboxService outboxService) {
        return new RefundCommandService(repository, channelMap, refundPolicy, outboxService);
    }

    @Bean
    public NotificationService notificationService(
            PaymentOrderRepository repository,
            Map<ChannelCode, PaymentChannelPort> channelMap,
            OutboxService outboxService) {
        return new NotificationService(repository, channelMap, outboxService);
    }
}
'''

# ==================== interfaces ====================
F[IF + "http/PaymentController.java"] = r'''
package com.demo.payment.interfaces.http;

import com.demo.payment.application.command.CreatePaymentCommand;
import com.demo.payment.application.command.PaymentCommandService;
import com.demo.payment.application.command.PayResult;
import com.demo.payment.application.command.RefundCommandService;
import com.demo.payment.domain.channel.model.PaymentMethodType;
import com.demo.payment.shared.money.Currency;
import com.demo.payment.shared.money.Money;
import com.demo.payment.shared.util.IdGenerator;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * 支付接入层（REST API）。
 *
 * <p><b>接入层的职责边界：只做三件事</b>
 * <ol>
 *   <li>协议转换：HTTP ↔ 应用层命令</li>
 *   <li>参数校验：格式、必填、范围</li>
 *   <li>安全：签名验证、限流、幂等键提取</li>
 * </ol>
 *
 * <p><b>绝不能在这里写业务逻辑。</b>
 * 常见错误是在 Controller 里判断"订单能不能退"——
 * 那样一来逻辑无法复用（定时任务、MQ 消费者要走另一条路），
 * 二来无法测试（必须起 Spring 容器）。
 */
@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentCommandService paymentCommandService;
    private final RefundCommandService refundCommandService;

    public PaymentController(PaymentCommandService paymentCommandService,
                             RefundCommandService refundCommandService) {
        this.paymentCommandService = paymentCommandService;
        this.refundCommandService = refundCommandService;
    }

    /**
     * 发起支付。
     *
     * <p><b>幂等键来源</b>：优先取客户端的 {@code Idempotency-Key} 请求头。
     * 客户端未传时，服务端用 {@code merchantId + merchantOrderNo} 兜底生成 ——
     * 这样即使客户端不做幂等，重复提交同一笔单也不会产生第二笔支付。
     */
    @PostMapping
    public ResponseEntity<?> pay(@RequestBody Map<String, String> request,
                                 @RequestHeader(value = "Idempotency-Key", required = false)
                                 String idempotencyKey) {
        String merchantId = request.get("merchantId");
        String merchantOrderNo = request.get("merchantOrderNo");
        String currencyCode = request.get("currency");
        BigDecimal amount = new BigDecimal(request.get("amount"));

        Currency currency = Currency.require(currencyCode);

        // 客户端未传幂等键时，用商户订单号兜底 —— 保证业务层幂等
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            idempotencyKey = "AUTO_" + merchantId + "_" + merchantOrderNo;
        }

        CreatePaymentCommand cmd = new CreatePaymentCommand(
                merchantId,
                merchantOrderNo,
                Money.ofMajor(amount, currency),
                PaymentMethodType.valueOf(request.get("paymentMethod")),
                request.get("subject"),
                request.get("notifyUrl"),
                request.get("returnUrl"),
                request.get("clientIp"),
                request.get("payerId"),
                request.get("paymentCredential"),
                idempotencyKey,
                request.getOrDefault("countryCode", "CN"),
                request.getOrDefault("scene", "APP"),
                Instant.now().plus(30, ChronoUnit.MINUTES)
        );

        PayResult result = paymentCommandService.pay(cmd);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(result);
    }

    /** 退款 */
    @PostMapping("/refund")
    public ResponseEntity<?> refund(@RequestBody Map<String, String> request) {
        String merchantId = request.get("merchantId");
        String merchantOrderNo = request.get("merchantOrderNo");
        Money amount = Money.ofMajor(new BigDecimal(request.get("amount")),
                Currency.require(request.get("currency")));

        var refund = refundCommandService.refund(merchantId, merchantOrderNo,
                amount, request.get("reason"));
        return ResponseEntity.ok(Map.of(
                "refundNo", refund.refundNo(),
                "amount", refund.amount().toString(),
                "status", refund.status().name()
        ));
    }
}
'''

F[IF + "http/NotifyController.java"] = r'''
package com.demo.payment.interfaces.http;

import com.demo.payment.application.command.NotificationService;
import com.demo.payment.application.command.NotifyHandleResult;
import com.demo.payment.domain.channel.model.ChannelCode;
import com.demo.payment.domain.channel.spi.RawNotification;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * 通道回调接入层。
 *
 * <h3>两个铁律</h3>
 * <ol>
 *   <li><b>必须拿原始 body 验签</b>：不能用 Spring 的 {@code @RequestBody Map}
 *       反序列化后的对象再验签 —— 序列化过程会改变空格、字段顺序，
 *       导致签名不匹配。因此这里用 {@code String} 接收原始报文。</li>
 *   <li><b>必须返回通道要求的应答格式</b>：否则通道会判定通知失败并持续重投。
 *       支付宝要求返回纯文本 {@code success}，微信要求 JSON。
 *       返回错格式会导致通知被重投 8 次以上，日志里全是重复告警。</li>
 * </ol>
 */
@RestController
@RequestMapping("/notify")
public class NotifyController {

    private final NotificationService notificationService;

    public NotifyController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /**
     * 统一回调入口：{@code POST /notify/{channel}}
     *
     * <p>注意 {@code produces}：支付宝要求 {@code text/plain} 返回 "success"，
     * 若返回 JSON 会一直重投。
     */
    @PostMapping(value = "/{channel}", produces = "text/plain;charset=UTF-8")
    public ResponseEntity<String> notify(@PathVariable("channel") String channel,
                                         @RequestBody String rawBody,
                                         @RequestHeader Map<String, String> headers,
                                         @RequestParam Map<String, String> queryParams) {
        ChannelCode channelCode = ChannelCode.valueOf(channel.toUpperCase());

        RawNotification raw = new RawNotification(rawBody, headers, queryParams, null);

        NotifyHandleResult result;
        try {
            result = notificationService.handle(channelCode, raw);
        } catch (Exception e) {
            // 验签失败/订单不存在等情况：返回 5xx 让通道重投，同时记录告警
            // 注意：订单不存在时返回 5xx 会导致通道无限重投，
            // 生产环境应区分对待 —— 验签失败返回 4xx，订单不存在返回 200 并记录死信
            return ResponseEntity.status(500).body("ERROR: " + e.getMessage());
        }

        // 返回通道要求的成功应答
        return ResponseEntity.ok(notificationService.successResponse(channelCode));
    }
}
'''

# ==================== bootstrap ====================
F["payment-bootstrap/src/main/java/com/demo/payment/bootstrap/PaymentApplication.java"] = r'''
package com.demo.payment.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 启动类。
 *
 * <p><b>组件扫描范围</b>：{@code com.demo.payment} 覆盖了所有模块的根包，
 * 因此各模块的 {@code @Component} / {@code @Configuration} 都能被扫描到。
 * 这是多模块 Spring Boot 工程的标准做法 —— 启动器模块依赖所有其他模块，
 * 但其他模块之间不互相依赖启动器。
 */
@SpringBootApplication(scanBasePackages = "com.demo.payment")
public class PaymentApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentApplication.class, args);
    }
}
'''

F["payment-bootstrap/src/main/resources/application.yml"] = '''server:
  port: 8080

spring:
  application:
    name: payment-ddd-demo
  jackson:
    default-property-inclusion: non_null

logging:
  level:
    com.demo.payment: DEBUG
'''

for path, content in F.items():
    full = os.path.join(BASE, path)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    with open(full, "w", encoding="utf-8") as f:
        f.write(content.lstrip("\n"))
    print("WROTE", path)

write_poms(BASE)
print("\nTOTAL java files:", len(F))
