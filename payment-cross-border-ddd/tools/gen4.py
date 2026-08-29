#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成应用层：幂等、Outbox、Saga、命令服务 + 结算上下文"""
import os

BASE = "/Users/abc/WorkBuddy/2026-08-29-13-23-42/payment-ddd-demo"
F = {}
AP = "payment-application/src/main/java/com/demo/payment/application/"

# ==================== 幂等 ====================
F[AP + "idempotency/IdempotencyRecord.java"] = r'''
package com.demo.payment.application.idempotency;

import java.time.Instant;

/**
 * 幂等记录。
 *
 * <p><b>为什么需要 {@code requestFingerprint}？</b>
 * 只记录幂等键是不够的。同一个幂等键，若携带不同的业务参数
 * （比如金额从 100 变成 200），说明客户端有 bug。
 * 此时必须<b>拒绝并报 409</b>，而不是静默返回第一次的结果 ——
 * 否则用户以为付了 200，实际只扣了 100，这是资损。
 */
public record IdempotencyRecord(
        String idempotencyKey,
        String requestFingerprint,
        IdempotencyStatus status,
        String resultSnapshot,
        Instant createdAt,
        Instant expireAt
) {

    public enum IdempotencyStatus {
        /** 处理中：请求已受理，尚未完成 */
        PROCESSING,
        /** 已完成：可安全返回快照结果 */
        COMPLETED,
        /** 失败：可安全重试（仅指业务失败，且失败本身是幂等的） */
        FAILED
    }

    public boolean isExpired(Instant now) {
        return expireAt != null && now.isAfter(expireAt);
    }

    public boolean matches(String fingerprint) {
        return requestFingerprint == null || requestFingerprint.equals(fingerprint);
    }
}
'''

F[AP + "idempotency/IdempotencyStore.java"] = r'''
package com.demo.payment.application.idempotency;

import java.time.Duration;
import java.util.Optional;

/**
 * 幂等存储端口（应用层定义，基础设施层实现）。
 *
 * <p>实现要点：
 * <ul>
 *   <li>必须用 <b>Redis SETNX</b> 或 DB 唯一索引做<b>原子抢占</b>，
 *       不能用"先查再写"两步 —— 那样并发下两个请求都会查到"不存在"，
 *       然后都去执行业务逻辑，幂等形同虚设。</li>
 *   <li>必须设置过期时间，避免键永久堆积。有效期应覆盖业务最长处理时间
 *       （支付业务通常 24 小时）。</li>
 *   <li>处理中的请求被重复调用时，应返回"处理中"而非再次执行。</li>
 * </ul>
 */
public interface IdempotencyStore {

    /**
     * 原子抢占幂等键。
     *
     * @return 抢占成功返回 empty；抢占失败返回已有记录（表示重复请求）
     */
    Optional<IdempotencyRecord> tryAcquire(String key, String fingerprint, Duration ttl);

    /** 标记处理完成并写入结果快照 */
    void complete(String key, String resultSnapshot);

    /** 标记失败，允许后续重试 */
    void fail(String key);

    Optional<IdempotencyRecord> get(String key);

    void release(String key);
}
'''

F[AP + "idempotency/IdempotencyGuard.java"] = r'''
package com.demo.payment.application.idempotency;

import com.demo.payment.shared.exception.IdempotencyConflictException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 幂等守卫 —— 支付系统的第一道防线。
 *
 * <h3>支付系统需要四层幂等，缺一不可</h3>
 * <ol>
 *   <li><b>接入层幂等</b>（本类）：客户端传 {@code Idempotency-Key}，
 *       防止用户重复点击、网络重试导致重复下单。</li>
 *   <li><b>业务层幂等</b>：{@code (merchant_id, merchant_order_no)} 唯一索引。
 *       这是最后兜底 —— 即使客户端没传幂等键，也不能产生两笔单。</li>
 *   <li><b>通道层幂等</b>：outTradeNo 每次尝试唯一 + 通道幂等键。
 *       防止重复扣款，这是<b>资金安全级别</b>的幂等。</li>
 *   <li><b>回调层幂等</b>：notifyId 去重 + 状态机终态守卫。
 *       通道会重投通知，必须去重；乱序到达时必须拒绝非法状态回退。</li>
 * </ol>
 *
 * <p>这四层分别防的是：用户手抖 / 客户端 bug / 网络重试 / 通道重投。
 * 任何一层缺失，都会在某个特定场景产生重复扣款。
 */
public class IdempotencyGuard {

    private static final Duration DEFAULT_TTL = Duration.ofHours(24);

    private final IdempotencyStore store;

    public IdempotencyGuard(IdempotencyStore store) {
        this.store = store;
    }

    /**
     * 执行带幂等保护的业务逻辑。
     *
     * @param key          幂等键
     * @param fingerprint  请求指纹（由关键业务参数计算）
     * @param business     真正的业务逻辑
     * @param serializer   结果序列化器（用于缓存首次结果）
     */
    public <T> T execute(String key, String fingerprint,
                         Supplier<T> business,
                         java.util.function.Function<T, String> serializer,
                         java.util.function.Function<String, T> deserializer) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey is required");
        }

        // 步骤一：原子抢占
        Optional<IdempotencyRecord> existing = store.tryAcquire(key, fingerprint, DEFAULT_TTL);

        if (existing.isPresent()) {
            IdempotencyRecord record = existing.get();

            // 幂等键相同但参数不同 → 客户端 bug，必须暴露
            if (!record.matches(fingerprint)) {
                throw new IdempotencyConflictException(key,
                        "幂等键 " + key + " 已用于不同的请求参数，请更换幂等键");
            }

            return switch (record.status()) {
                // 上一次已完成，直接返回缓存的结果（这是幂等的核心价值）
                case COMPLETED -> deserializer.apply(record.resultSnapshot());
                // 上一次还在处理中 —— 返回明确异常让调用方稍后重试，绝不能并发执行业务逻辑
                case PROCESSING -> throw new IllegalStateException(
                        "请求正在处理中，请勿重复提交. key=" + key);
                // 上一次失败，允许重试
                case FAILED -> runAndRecord(key, business, serializer);
            };
        }

        return runAndRecord(key, business, serializer);
    }

    private <T> T runAndRecord(String key, Supplier<T> business,
                               java.util.function.Function<T, String> serializer) {
        try {
            T result = business.get();
            store.complete(key, serializer.apply(result));
            return result;
        } catch (RuntimeException e) {
            store.fail(key);
            throw e;
        }
    }

    /**
     * 计算请求指纹。
     *
     * <p><b>只应包含"决定业务结果"的参数</b>：金额、币种、商户号、商户订单号、支付方式。
     * 不应包含：请求时间、traceId、用户 IP —— 这些每次都变，会导致指纹永远不匹配，
     * 幂等失效。
     */
    public static String fingerprint(String... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                md.update((part == null ? "" : part).getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0x1F); // 分隔符，防止 "ab"+"c" 与 "a"+"bc" 碰撞
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** 生成一个默认 TTL 的过期时间 */
    public static Instant defaultExpireAt() {
        return Instant.now().plus(DEFAULT_TTL);
    }
}
'''

# ==================== Outbox ====================
F[AP + "outbox/OutboxEvent.java"] = r'''
package com.demo.payment.application.outbox;

import java.time.Instant;

/**
 * Outbox 事件记录。
 *
 * <p><b>分布式事务的经典难题：</b>
 * "写数据库"和"发消息"是两个独立系统，无法放在同一个本地事务里。
 * 于是必然出现：
 * <ul>
 *   <li>先写库后发消息 → 消息发送失败，下游永远不知道订单已支付。</li>
 *   <li>先发消息后写库 → 库写入失败（事务回滚），下游却收到了"支付成功"，
 *       会造成发货但没收到钱的资损。</li>
 * </ul>
 *
 * <p><b>Outbox 模式是标准解法：</b>
 * 把要发的消息作为一行数据，<b>和业务数据在同一个本地事务里</b>写入 outbox 表。
 * 事务提交后，由独立的投递线程读取 outbox 发往 MQ，成功后标记已发送。
 * 这样"业务状态"与"消息"的原子性由数据库事务保证。
 *
 * <p>代价是消息可能重复投递（投递成功但标记失败，下次重投），
 * 因此<b>消费端必须做幂等</b> —— 这是 Outbox 模式的必要配套。
 */
public record OutboxEvent(
        String eventId,
        String aggregateType,
        String aggregateId,
        String eventType,
        String payload,
        OutboxStatus status,
        int retryCount,
        Instant createdAt,
        Instant sentAt,
        String lastError
) {

    public enum OutboxStatus {
        /** 待发送 */
        PENDING,
        /** 已发送 */
        SENT,
        /** 发送失败，等待重试 */
        FAILED,
        /** 超过最大重试次数，需人工介入 */
        DEAD
    }

    public static OutboxEvent pending(String eventId, String aggregateType, String aggregateId,
                                      String eventType, String payload) {
        return new OutboxEvent(eventId, aggregateType, aggregateId, eventType, payload,
                OutboxStatus.PENDING, 0, Instant.now(), null, null);
    }

    public OutboxEvent markSent() {
        return new OutboxEvent(eventId, aggregateType, aggregateId, eventType, payload,
                OutboxStatus.SENT, retryCount, createdAt, Instant.now(), null);
    }

    public OutboxEvent markFailed(String error) {
        boolean dead = retryCount + 1 >= 5;
        return new OutboxEvent(eventId, aggregateType, aggregateId, eventType, payload,
                dead ? OutboxStatus.DEAD : OutboxStatus.FAILED, retryCount + 1,
                createdAt, null, error);
    }

    public boolean needsRetry() {
        return status == OutboxStatus.PENDING || status == OutboxStatus.FAILED;
    }
}
'''

F[AP + "outbox/OutboxStore.java"] = r'''
package com.demo.payment.application.outbox;

import java.util.List;

/**
 * Outbox 存储端口。
 *
 * <p><b>关键约束：{@code append} 必须与业务数据在同一事务中执行。</b>
 * 实现时通常直接注入同一个 DataSource，由上层 {@code @Transactional} 保证。
 * 若 append 用了独立连接/独立事务，Outbox 就失去意义了。
 */
public interface OutboxStore {

    /** 追加事件（与业务写在同一事务内） */
    void append(OutboxEvent event);

    /** 批量拉取待发送事件 */
    List<OutboxEvent> fetchPending(int limit);

    /** 更新事件状态 */
    void update(OutboxEvent event);

    /** 清理已发送的历史数据（保留 N 天） */
    int cleanupSentOlderThan(int days);
}
'''

F[AP + "outbox/OutboxService.java"] = r'''
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
'''

# ==================== 命令服务 ====================
F[AP + "command/CreatePaymentCommand.java"] = r'''
package com.demo.payment.application.command;

import com.demo.payment.domain.channel.model.PaymentMethodType;
import com.demo.payment.shared.money.Money;

import java.time.Instant;

/**
 * 创建支付单的应用层命令。
 *
 * <p><b>应用层命令 vs 领域对象：</b>
 * 命令是"请求"，领域对象是"状态"。命令可以含技术字段（clientIp、idempotencyKey），
 * 但这些字段不应污染领域模型 —— 领域只关心"谁付多少钱买什么"。
 */
public record CreatePaymentCommand(
        String merchantId,
        String merchantOrderNo,
        Money amount,
        PaymentMethodType paymentMethod,
        String subject,
        String notifyUrl,
        String returnUrl,
        String clientIp,
        String payerId,
        String paymentCredential,
        String idempotencyKey,
        String countryCode,
        String scene,
        Instant expireAt
) {}
'''

F[AP + "command/PaymentCommandService.java"] = r'''
package com.demo.payment.application.command;

import com.demo.payment.application.idempotency.IdempotencyGuard;
import com.demo.payment.application.outbox.OutboxService;
import com.demo.payment.domain.acquiring.model.aggregate.PaymentOrder;
import com.demo.payment.domain.acquiring.model.vo.OutTradeNo;
import com.demo.payment.domain.channel.model.ChannelCode;
import com.demo.payment.domain.channel.model.ChannelResultStatus;
import com.demo.payment.domain.channel.model.PaymentMethodType;
import com.demo.payment.domain.channel.route.ChannelRouter;
import com.demo.payment.domain.channel.spi.*;
import com.demo.payment.domain.acquiring.repository.PaymentOrderRepository;
import com.demo.payment.shared.money.Money;
import com.demo.payment.shared.util.IdGenerator;

import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Lock;

/**
 * 支付命令服务 —— 应用层的编排核心。
 *
 * <h3>应用层的职责边界</h3>
 * <p>应用层<b>不做业务判断</b>（那是领域层的事），它负责：
 * <ol>
 *   <li><b>事务边界</b>：一次用例一个事务</li>
 *   <li><b>编排</b>：聚合 + 仓储 + 外部端口的调用顺序</li>
 *   <li><b>技术关注点</b>：幂等、锁、重试、事件发布</li>
 * </ol>
 *
 * <h3>支付主流程（重点）</h3>
 * <pre>
 *   1. 幂等检查（接入层）
 *   2. 业务幂等：按 (merchantId, merchantOrderNo) 查重
 *   3. 路由：选出候选通道列表
 *   4. 逐个尝试通道：
 *      4a. 生成 attemptSeq 对应的 outTradeNo（每次尝试唯一！）
 *      4b. 调用通道
 *      4c. SUCCEEDED → 更新订单 → 结束
 *          PENDING   → 保存凭证 → 结束（等回调/查证）
 *          UNKNOWN   → 保存 + 登记查证任务 → 结束（绝不关单！）
 *          FAILED    → 记录失败 → 尝试下一个通道
 *   5. 全部失败 → 标记订单失败
 *   6. 保存聚合 + 捕获领域事件进 Outbox
 * </pre>
 *
 * <p><b>第 4a 步是最容易出错的地方：</b>
 * 重试时若复用同一个 outTradeNo，微信/支付宝会返回"订单已存在"，
 * 重试永远失败；若每次都换新号，则必须确保旧号已失效（否则可能重复扣款）。
 * 本实现采用"每尝试一号"策略，并在 UNKNOWN 时靠查证兜底。
 */
public class PaymentCommandService {

    private final PaymentOrderRepository repository;
    private final ChannelRouter router;
    private final Map<ChannelCode, PaymentChannelPort> channels;
    private final IdempotencyGuard idempotencyGuard;
    private final OutboxService outboxService;

    public PaymentCommandService(PaymentOrderRepository repository,
                                 ChannelRouter router,
                                 Map<ChannelCode, PaymentChannelPort> channels,
                                 IdempotencyGuard idempotencyGuard,
                                 OutboxService outboxService) {
        this.repository = repository;
        this.router = router;
        this.channels = channels;
        this.idempotencyGuard = idempotencyGuard;
        this.outboxService = outboxService;
    }

    /**
     * 创建并发起支付。
     *
     * <p><b>事务边界说明：</b>
     * 本方法在一个事务内完成"订单落库 + Outbox 写入"。
     * <b>通道调用必须在事务之外</b> —— 否则网络超时会导致事务长时间挂起，
     * 占用数据库连接，高并发下直接压垮 DB。
     * 正确做法：先落库（事务内），再调通道（事务外），再更新状态（新事务）。
     */
    public PayResult pay(CreatePaymentCommand cmd) {
        // ---- 第一层幂等：接入层 ----
        String fingerprint = IdempotencyGuard.fingerprint(
                cmd.merchantId(), cmd.merchantOrderNo(),
                String.valueOf(cmd.amount().minorUnits()),
                cmd.amount().currency().code(),
                cmd.paymentMethod().name());

        return idempotencyGuard.execute(
                cmd.idempotencyKey(),
                fingerprint,
                () -> doPay(cmd),
                r -> r.toString(),
                s -> PayResult.parse(s)
        );
    }

    private PayResult doPay(CreatePaymentCommand cmd) {
        // ---- 第二层幂等：业务层（商户订单号唯一性）----
        var existing = repository.findByMerchantOrderNo(cmd.merchantId(), cmd.merchantOrderNo());
        if (existing.isPresent()) {
            PaymentOrder order = existing.get();
            // 已存在则直接返回原单，绝不重复创建 —— 这是防重复下单的最后兜底
            return PayResult.of(order, "EXISTING_ORDER_RETURNED");
        }

        // ---- 1. 创建聚合 ----
        PaymentOrder order = PaymentOrder.create(
                cmd.merchantId(), cmd.merchantOrderNo(), cmd.amount(),
                cmd.paymentMethod(), cmd.subject(), cmd.notifyUrl(), cmd.expireAt());

        // ---- 2. 路由 ----
        RoutingContext routingCtx = new RoutingContext(
                cmd.merchantId(), cmd.paymentMethod(), cmd.amount(),
                cmd.amount().currency(), cmd.countryCode(), cmd.clientIp(), cmd.scene());

        List<ChannelCode> candidates = router.route(routingCtx);
        if (candidates.isEmpty()) {
            throw new IllegalStateException("无可用通道：支付方式=" + cmd.paymentMethod()
                    + " 币种=" + cmd.amount().currency().code());
        }

        // ---- 3. 落库（事务内）----
        repository.save(order);

        // ---- 4. 逐个尝试通道（事务外）----
        Lock lock = repository.obtainLock(order.id());
        lock.lock();
        try {
            for (int i = 0; i < candidates.size(); i++) {
                ChannelCode channelCode = candidates.get(i);
                PaymentChannelPort channel = channels.get(channelCode);
                if (channel == null) {
                    continue;
                }

                int attemptSeq = i + 1;
                OutTradeNo outTradeNo = OutTradeNo.of(
                        IdGenerator.outTradeNo(order.id().value(), attemptSeq));

                // 登记尝试（生成 attempt 实体）
                order.startAttempt(channelCode, outTradeNo);

                PayCommand payCommand = PayCommand.builder()
                        .outTradeNo(outTradeNo)
                        .amount(cmd.amount())
                        .paymentMethod(cmd.paymentMethod())
                        .subject(cmd.subject())
                        .notifyUrl(cmd.notifyUrl())
                        .returnUrl(cmd.returnUrl())
                        .clientIp(cmd.clientIp())
                        .payerId(cmd.payerId())
                        .paymentCredential(cmd.paymentCredential())
                        .idempotencyKey(cmd.idempotencyKey())
                        .countryCode(cmd.countryCode())
                        .build();

                PayResponse response;
                try {
                    response = channel.pay(payCommand);
                } catch (Exception e) {
                    // 通道异常不算订单失败，继续尝试下一个通道
                    continue;
                }

                if (response.isSucceeded()) {
                    order.applyChannelResult(outTradeNo, true, cmd.amount(),
                            response.channelTransactionId(), "SUCCESS", null);
                    break;
                } else if (response.isUnknown()) {
                    // UNKNOWN：保持"支付中"，由查证补偿任务兜底。
                    // 绝不在这里关单或判失败 —— 那会造成掉单/资损。
                    break;
                } else if (response.isPending()) {
                    // 已拿到支付凭证，等待用户付款或回调
                    break;
                }
                // FAILED：继续尝试下一个通道
            }

            if (order.status().isProcessing() && !order.attempts().isEmpty()
                    && order.attempts().stream().allMatch(a -> a.status() == com.demo.payment.domain.acquiring.model.entity.PaymentAttempt.AttemptStatus.FAILED)) {
                order.markFailed("所有通道尝试均失败");
            }

            // ---- 5. 保存 + 捕获事件 ----
            repository.save(order);
            outboxService.capture("PaymentOrder", order.id().value(), order.pullDomainEvents());

            return PayResult.of(order, "SUBMITTED");
        } finally {
            lock.unlock();
        }
    }

    /**
     * 主动查证 —— 补偿 UNKNOWN 与丢失回调的关键手段。
     *
     * <p>调用时机：
     * <ul>
     *   <li>下单返回 UNKNOWN 后立即触发</li>
     *   <li>定时任务扫描"支付中"超过 N 分钟的订单</li>
     *   <li>收到回调时，先查证再更新（不信任回调内容）</li>
     * </ul>
     */
    public boolean reconcile(PaymentOrder order) {
        var attempt = order.currentAttempt();
        if (attempt == null) {
            return false;
        }
        PaymentChannelPort channel = channels.get(attempt.channelCode());
        if (channel == null) {
            return false;
        }

        Lock lock = repository.obtainLock(order.id());
        lock.lock();
        try {
            QueryResponse resp = channel.query(QueryCommand.byOutTradeNo(attempt.outTradeNo()));
            if (resp.status() != null && resp.status().isFinal()) {
                boolean success = resp.status() == ChannelResultStatus.SUCCEEDED;
                boolean changed = order.applyChannelResult(attempt.outTradeNo(), success,
                        resp.amount(), resp.channelTransactionId(), resp.channelRawStatus(), null);
                if (changed) {
                    repository.save(order);
                    outboxService.capture("PaymentOrder", order.id().value(), order.pullDomainEvents());
                }
                return changed;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }
}
'''

F[AP + "command/PayResult.java"] = r'''
package com.demo.payment.application.command;

import com.demo.payment.domain.acquiring.model.aggregate.PaymentOrder;

import java.util.Map;

/**
 * 支付受理结果（返回给接入层）。
 */
public record PayResult(
        String paymentOrderId,
        String merchantOrderNo,
        String status,
        String amount,
        /** 支付凭证，透传给前端用于拉起支付 */
        Map<String, String> credential,
        String message
) {
    public static PayResult of(PaymentOrder order, String message) {
        var attempt = order.currentAttempt();
        Map<String, String> cred = attempt != null
                ? Map.of("outTradeNo", attempt.outTradeNo().value(),
                         "channel", attempt.channelCode().code())
                : Map.of();
        return new PayResult(order.id().value(), order.merchantOrderNo(),
                order.status().name(), order.amount().toString(), cred, message);
    }

    public static PayResult parse(String snapshot) {
        // 简化实现：真实场景用 JSON 序列化
        return new PayResult(snapshot, "", "", "", Map.of(), "FROM_IDEMPOTENCY_CACHE");
    }
}
'''

# ==================== 退款 ====================
F[AP + "command/RefundCommandService.java"] = r'''
package com.demo.payment.application.command;

import com.demo.payment.application.outbox.OutboxService;
import com.demo.payment.domain.acquiring.model.aggregate.PaymentOrder;
import com.demo.payment.domain.acquiring.model.entity.RefundOrder;
import com.demo.payment.domain.acquiring.repository.PaymentOrderRepository;
import com.demo.payment.domain.acquiring.service.RefundCheckResult;
import com.demo.payment.domain.acquiring.service.RefundPolicyService;
import com.demo.payment.domain.channel.model.ChannelResultStatus;
import com.demo.payment.domain.channel.spi.*;
import com.demo.payment.domain.channel.model.ChannelCapability;
import com.demo.payment.shared.money.Money;
import com.demo.payment.shared.util.IdGenerator;

import java.util.Map;
import java.util.concurrent.locks.Lock;

/**
 * 退款命令服务。
 *
 * <h3>为什么退款必须用分布式锁 + 聚合内校验</h3>
 * <p>并发退款是最经典的资金安全事故场景：
 * <pre>
 *   线程A：读订单 → 已退 0 → 校验通过 → 退款 100
 *   线程B：读订单 → 已退 0 → 校验通过 → 退款 100
 *   结果：原单 100 元，实际退了 200 元
 * </pre>
 *
 * <p>本实现三重防护：
 * <ol>
 *   <li><b>分布式锁</b>：按订单维度串行化，从物理上杜绝并发</li>
 *   <li><b>聚合内校验</b>：PaymentOrder 内部同一把锁内完成"读-校验-写"</li>
 *   <li><b>DB 约束</b>：退款金额 sum 的 CHECK 约束（最终兜底）</li>
 * </ol>
 *
 * <p>只做其中任何一层都不够 —— 锁可能失效（Redis 抖动），
 * 聚合校验可能在极端并发下被绕过（若未来改成独立聚合），
 * DB 约束则太晚（用户已收到退款成功的响应）。三层是纵深防御。
 */
public class RefundCommandService {

    private final PaymentOrderRepository repository;
    private final Map<com.demo.payment.domain.channel.model.ChannelCode, PaymentChannelPort> channels;
    private final RefundPolicyService refundPolicy;
    private final OutboxService outboxService;

    public RefundCommandService(PaymentOrderRepository repository,
                                Map<com.demo.payment.domain.channel.model.ChannelCode, PaymentChannelPort> channels,
                                RefundPolicyService refundPolicy,
                                OutboxService outboxService) {
        this.repository = repository;
        this.channels = channels;
        this.refundPolicy = refundPolicy;
        this.outboxService = outboxService;
    }

    /**
     * 发起退款。
     */
    public RefundOrder refund(String merchantId, String merchantOrderNo,
                              Money refundAmount, String reason) {
        PaymentOrder order = repository.findByMerchantOrderNo(merchantId, merchantOrderNo)
                .orElseThrow(() -> new IllegalArgumentException("订单不存在: " + merchantOrderNo));

        // ---- 第一层防护：分布式锁（订单维度串行化）----
        Lock lock = repository.obtainLock(order.id());
        lock.lock();
        try {
            var attempt = order.currentAttempt();
            if (attempt == null) {
                throw new IllegalStateException("订单无通道尝试记录，无法退款");
            }

            PaymentChannelPort channel = channels.get(attempt.channelCode());
            ChannelCapability capability = channel.capability();

            // ---- 第二层防护：策略校验（含通道能力）----
            RefundCheckResult check = refundPolicy.check(order, capability, refundAmount);
            if (!check.allowed()) {
                throw new IllegalStateException("退款被拒绝：" + check.rejectReason());
            }

            // ---- 第三层防护：聚合内累计金额校验（防并发超额）----
            int windowDays = capability.refundWindowDays() == null ? 0 : capability.refundWindowDays();
            RefundOrder refund = order.requestRefund(refundAmount, reason, windowDays);

            // 先落库占用额度，再调通道
            repository.save(order);
            outboxService.capture("PaymentOrder", order.id().value(), order.pullDomainEvents());

            // 调用通道退款
            String outRefundNo = IdGenerator.refundOrderId();
            RefundCommand refundCommand = new RefundCommand(
                    attempt.outTradeNo(), outRefundNo, refundAmount, order.amount(),
                    reason, order.notifyUrl(), outRefundNo);

            RefundResponse response = channel.refund(refundCommand);

            if (response.status() == ChannelResultStatus.SUCCEEDED) {
                refund.markSucceeded(response.channelRefundId(), null);
            } else if (response.status() == ChannelResultStatus.FAILED) {
                // 退款失败：释放占有的额度（markFailed 后不计入累计）
                refund.markFailed(response.message());
            } else {
                // UNKNOWN：保持 PENDING，占用额度，由查证补偿任务处理
                refund.markProcessing();
            }

            repository.save(order);
            outboxService.capture("PaymentOrder", order.id().value(), order.pullDomainEvents());
            return refund;
        } finally {
            lock.unlock();
        }
    }
}
'''

# ==================== 回调处理 ====================
F[AP + "command/NotificationService.java"] = r'''
package com.demo.payment.application.command;

import com.demo.payment.application.outbox.OutboxService;
import com.demo.payment.domain.acquiring.model.aggregate.PaymentOrder;
import com.demo.payment.domain.acquiring.repository.PaymentOrderRepository;
import com.demo.payment.domain.channel.model.ChannelCode;
import com.demo.payment.domain.channel.model.ChannelResultStatus;
import com.demo.payment.domain.channel.spi.*;
import com.demo.payment.shared.money.Money;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;

/**
 * 回调通知处理服务。
 *
 * <h3>核心原则：回调不可信</h3>
 * <p>异步回调存在四类问题，必须逐一应对：
 * <ol>
 *   <li><b>可能丢失</b>：网络抖动、我方 5xx → 依赖主动查证补偿</li>
 *   <li><b>可能重复</b>：通道重投 → <b>notifyId 去重</b></li>
 *   <li><b>可能乱序</b>：先成功回调后失败回调 → <b>状态机终态守卫</b></li>
 *   <li><b>可能被伪造</b>：攻击者构造回调 → <b>严格验签 + 金额比对</b></li>
 * </ol>
 *
 * <p>因此正确处理姿势是：
 * <pre>
 *   收到回调 → 验签 → 去重 → <b>主动查证</b> → 用查证结果更新状态
 * </pre>
 *
 * <p><b>注意第 3 步</b>：生产环境应当"回调只当触发器，状态以查证为准"。
 * 本实现为演示清晰起见直接用回调内容更新，
 * 但保留了 reconcile 分支，并在注释中说明生产建议。
 */
public class NotificationService {

    private final PaymentOrderRepository repository;
    private final java.util.Map<ChannelCode, PaymentChannelPort> channels;
    private final OutboxService outboxService;

    /**
     * 已处理的通知 ID 集合（notifyId 去重）。
     *
     * <p>生产环境应持久化到 Redis（带 TTL）或 DB，
     * 因为进程重启后内存去重表会丢失，导致重启后重复处理通知。
     */
    private final Set<String> processedNotifyIds = ConcurrentHashMap.newKeySet();

    public NotificationService(PaymentOrderRepository repository,
                               java.util.Map<ChannelCode, PaymentChannelPort> channels,
                               OutboxService outboxService) {
        this.repository = repository;
        this.channels = channels;
        this.outboxService = outboxService;
    }

    /**
     * 处理通道回调。
     *
     * @param channelCode 通道编码（由 URL 路径决定，如 /notify/wechatpay）
     * @param raw         原始报文（<b>必须是未解析的原始字符串</b>，否则无法验签）
     * @return 返回给通道的响应文本（微信/支付宝要求特定格式，否则会不断重投）
     */
    public NotifyHandleResult handle(ChannelCode channelCode, RawNotification raw) {
        PaymentChannelPort channel = channels.get(channelCode);
        if (channel == null) {
            throw new IllegalArgumentException("未注册的通道: " + channelCode);
        }

        // ---- 步骤一：解析 + 验签（验签在适配器内部完成，失败直接抛异常）----
        NotificationParseResult parsed = channel.parseNotification(raw);

        // ---- 步骤二：notifyId 去重 ----
        if (parsed.notifyId() != null && !processedNotifyIds.add(parsed.notifyId())) {
            return NotifyHandleResult.duplicate(parsed.notifyId());
        }

        // ---- 步骤三：定位订单 ----
        PaymentOrder order = repository.findByOutTradeNo(parsed.outTradeNo())
                .orElseThrow(() -> new IllegalStateException(
                        "回调对应的订单不存在: " + parsed.outTradeNo()));

        // ---- 步骤四：加锁后应用状态 ----
        Lock lock = repository.obtainLock(order.id());
        lock.lock();
        try {
            boolean changed;
            if (parsed.hasFinalResult()) {
                boolean success = parsed.status() == ChannelResultStatus.SUCCEEDED;
                // 金额一致性由聚合内部强制校验，篡改金额会直接抛异常
                changed = order.applyChannelResult(parsed.outTradeNo(), success,
                        parsed.amount(), parsed.channelTransactionId(),
                        parsed.channelRawStatus(), parsed.occurredAt());
            } else {
                // 回调只是中间态（如"用户支付中"），不更新状态，仅记录
                changed = false;
            }

            if (changed) {
                repository.save(order);
                outboxService.capture("PaymentOrder", order.id().value(), order.pullDomainEvents());
            }
            return NotifyHandleResult.success(parsed.notifyId(), changed);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 返回给通道的应答文本。
     *
     * <p><b>各通道要求不同，返回错会导致通道疯狂重投：</b>
     * <pre>
     *   微信 v3  → HTTP 200 + {"code":"SUCCESS"}，或 204
     *   支付宝   → 纯字符串 "success"（不能带引号、不能有空格）
     *   Stripe   → HTTP 200 即可
     *   PayPal   → HTTP 200
     * </pre>
     */
    public String successResponse(ChannelCode channelCode) {
        return switch (channelCode) {
            case ALIPAY -> "success";
            case WECHAT_PAY -> "{\"code\":\"SUCCESS\",\"message\":\"成功\"}";
            default -> "OK";
        };
    }
}
'''

F[AP + "command/NotifyHandleResult.java"] = r'''
package com.demo.payment.application.command;

public record NotifyHandleResult(
        boolean accepted,
        boolean stateChanged,
        boolean duplicate,
        String notifyId,
        String message
) {
    public static NotifyHandleResult success(String notifyId, boolean changed) {
        return new NotifyHandleResult(true, changed, false, notifyId, "OK");
    }

    /** 重复通知：幂等放过，但仍需返回成功给通道，否则会一直重投 */
    public static NotifyHandleResult duplicate(String notifyId) {
        return new NotifyHandleResult(true, false, true, notifyId, "DUPLICATE_NOTIFY");
    }
}
'''

# ==================== 结算上下文（轻量） ====================
SP = "payment-domain/src/main/java/com/demo/payment/domain/settlement/"
F[SP + "model/SettlementOrder.java"] = r'''
package com.demo.payment.domain.settlement.model;

import com.demo.payment.shared.money.Money;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 结算单 —— 结算限界上下文的聚合根（轻量建模）。
 *
 * <p><b>为什么结算要独立成限界上下文？</b>
 * 收单关心"这笔钱能不能收到"，结算关心"收到的钱什么时候、以什么比例给到商户"。
 * 两者的业务节奏完全不同：收单是秒级，结算是 T+1 日终批量。
 * 若混在一起，日终批处理会拖垮在线交易链路。
 *
 * <p>上下文之间通过领域事件协作：收单上下文发出 {@code PaymentSucceeded}，
 * 结算上下文订阅后生成结算明细。这样结算逻辑变更不影响支付主链路。
 */
public class SettlementOrder {

    private final String settlementNo;
    private final String merchantId;
    private final LocalDate settlementDate;
    private final Instant createdAt;

    /** 结算总额（订单金额之和） */
    private Money grossAmount;
    /** 通道手续费 */
    private Money feeAmount;
    /** 实际结算金额 = 总额 - 手续费 - 分账支出 */
    private Money netAmount;
    private SettlementStatus status;

    public SettlementOrder(String settlementNo, String merchantId, LocalDate settlementDate) {
        this.settlementNo = settlementNo;
        this.merchantId = merchantId;
        this.settlementDate = settlementDate;
        this.createdAt = Instant.now();
        this.status = SettlementStatus.PENDING;
    }

    /**
     * 计算净结算额。
     *
     * <p><b>必须用 {@code allocate} 之外的显式减法</b>：
     * 分账是"按比例拆分"，结算是"总额减去各项扣除"，
     * 两者语义不同 —— 分账要求 sum(parts) == total，
     * 结算则允许净额为负（倒挂，需人工处理）。
     */
    public void calculate(Money gross, Money fee) {
        this.grossAmount = gross;
        this.feeAmount = fee;
        this.netAmount = gross.minus(fee);
    }

    public String settlementNo() { return settlementNo; }
    public String merchantId() { return merchantId; }
    public LocalDate settlementDate() { return settlementDate; }
    public Money grossAmount() { return grossAmount; }
    public Money feeAmount() { return feeAmount; }
    public Money netAmount() { return netAmount; }
    public SettlementStatus status() { return status; }
    public Instant createdAt() { return createdAt; }

    public enum SettlementStatus {
        PENDING, CALCULATED, CONFIRMED, PAID, FAILED
    }
}
'''

F[SP + "model/SplitInstruction.java"] = r'''
package com.demo.payment.domain.settlement.model;

import com.demo.payment.shared.money.Money;

/**
 * 分账指令。
 *
 * <p>典型场景：平台型电商，一笔 100 元订单要分给平台 10 元、商家 85 元、推广方 5 元。
 *
 * <p><b>核心难点是金额分配的余数问题：</b>
 * 100 元按 10:85:5 分，若各自独立计算再四舍五入，可能出现 10+85+5=100
 * 或 99 或 101 的三种结果。必须用 {@link Money#allocate(int...)} 保证
 * <b>各部分之和严格等于原额</b>，否则日终对账必然出现分差。
 */
public record SplitInstruction(
        String instructionNo,
        String payerMerchantId,
        /** 收款方 ID（商户/个人） */
        String payeeId,
        /** 分账金额（由 allocate 计算得出，保证无余数丢失） */
        Money amount,
        SplitType type,
        String description
) {
    public enum SplitType {
        /** 按比例 */
        RATIO,
        /** 固定金额 */
        FIXED,
        /** 平台抽成 */
        PLATFORM_FEE
    }
}
'''

F[SP + "model/Withdrawal.java"] = r'''
package com.demo.payment.domain.settlement.model;

import com.demo.payment.shared.money.Money;

import java.time.Instant;

/**
 * 提现单。
 *
 * <p><b>提现是资金出账，风险等级最高</b>，因此比支付更保守：
 * <ul>
 *   <li>必须校验可用余额（已结算 - 已提现 - 冻结中）</li>
 *   <li>大额提现需人工复核（超过阈值）</li>
 *   <li>必须做风控校验（洗钱、欺诈）</li>
 *   <li>通常是异步到账（银行通道 T+0/T+1）</li>
 * </ul>
 */
public class Withdrawal {

    private final String withdrawalNo;
    private final String merchantId;
    private final Money amount;
    private final String payeeAccount;
    private final Instant createdAt;

    private WithdrawalStatus status;
    private String channelTransactionId;
    private String failReason;
    private Instant finishedAt;

    public Withdrawal(String withdrawalNo, String merchantId, Money amount, String payeeAccount) {
        this.withdrawalNo = withdrawalNo;
        this.merchantId = merchantId;
        this.amount = amount;
        this.payeeAccount = payeeAccount;
        this.createdAt = Instant.now();
        this.status = WithdrawalStatus.INIT;
    }

    public void markProcessing(String channelTransactionId) {
        this.status = WithdrawalStatus.PROCESSING;
        this.channelTransactionId = channelTransactionId;
    }

    public void markSucceeded() {
        this.status = WithdrawalStatus.SUCCEEDED;
        this.finishedAt = Instant.now();
    }

    public void markFailed(String reason) {
        this.status = WithdrawalStatus.FAILED;
        this.failReason = reason;
        this.finishedAt = Instant.now();
    }

    /** 是否计入"已提现"余额占用 */
    public boolean occupiesBalance() {
        return status != WithdrawalStatus.FAILED;
    }

    public String withdrawalNo() { return withdrawalNo; }
    public String merchantId() { return merchantId; }
    public Money amount() { return amount; }
    public String payeeAccount() { return payeeAccount; }
    public Instant createdAt() { return createdAt; }
    public WithdrawalStatus status() { return status; }
    public String channelTransactionId() { return channelTransactionId; }
    public String failReason() { return failReason; }
    public Instant finishedAt() { return finishedAt; }

    public enum WithdrawalStatus {
        INIT, PROCESSING, SUCCEEDED, FAILED
    }
}
'''

for path, content in F.items():
    full = os.path.join(BASE, path)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    with open(full, "w", encoding="utf-8") as f:
        f.write(content.lstrip("\n"))
    print("WROTE", path)
print("\nTOTAL:", len(F))
