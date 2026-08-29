#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成 shared-kernel 与 domain 层剩余文件"""
import os

BASE = "/Users/abc/WorkBuddy/2026-08-29-13-23-42/payment-ddd-demo"

FILES = {}

# ============ shared-kernel ============
FILES["payment-shared-kernel/src/main/java/com/demo/payment/shared/event/DomainEvent.java"] = r'''
package com.demo.payment.shared.event;

import java.time.Instant;

/**
 * 领域事件标记接口。
 *
 * <p>领域事件表达的是"领域中已经发生的事实"，用过去式命名（PaymentSucceeded 而非 PaySuccess）。
 * 它有三个用途：
 * <ol>
 *   <li><b>解耦限界上下文</b>：支付成功后要通知结算、账务、风控、营销，
 *       若用同步调用，支付主链路会被这些下游拖垮。改为事件驱动后，
 *       支付只负责"宣布事实"，谁关心谁订阅。</li>
 *   <li><b>审计溯源</b>：事件流就是完整的资金流水时间线，出问题能回放。</li>
 *   <li><b>可靠异步</b>：配合 Outbox 模式，保证"状态变更"与"事件发布"原子性。</li>
 * </ol>
 */
public interface DomainEvent {

    /** 事件发生时间 */
    Instant occurredAt();

    /** 事件唯一标识，用于消费端幂等去重 */
    default String eventId() {
        return java.util.UUID.randomUUID().toString();
    }

    /** 聚合根 ID，用于分区与追踪 */
    String aggregateId();
}
'''

FILES["payment-shared-kernel/src/main/java/com/demo/payment/shared/event/EventPublisher.java"] = r'''
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
'''

FILES["payment-shared-kernel/src/main/java/com/demo/payment/shared/exception/PaymentException.java"] = r'''
package com.demo.payment.shared.exception;

/**
 * 支付业务异常基类。
 *
 * <p>区分三类异常是支付系统错误处理的关键：
 * <ul>
 *   <li>{@code PaymentException}：业务规则拒绝（余额不足、超过限额、订单已关闭）。
 *       <b>不需要重试</b>，直接返回用户。</li>
 *   <li>{@code ChannelInfrastructureException}：基础设施故障（网络超时、证书缺失）。
 *       <b>需要重试或切通道</b>。</li>
 *   <li>{@code IdempotencyConflictException}：幂等键冲突（同 key 不同参数）。
 *       <b>必须返回 409</b>，绝不能当作新请求处理。</li>
 * </ul>
 */
public class PaymentException extends RuntimeException {

    private final String code;

    public PaymentException(String code, String message) {
        super(message);
        this.code = code;
    }

    public PaymentException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() { return code; }
}
'''

FILES["payment-shared-kernel/src/main/java/com/demo/payment/shared/exception/ChannelInfrastructureException.java"] = r'''
package com.demo.payment.shared.exception;

/**
 * 通道基础设施异常 —— 唯一允许触发重试/切通道的异常类型。
 *
 * <p><b>为什么必须和 PaymentException 分开？</b>
 * 如果把"网络超时"和"余额不足"都抛成同一种异常，上层就无法区分
 * "该重试"和"该告诉用户换张卡"。结果是：要么对业务失败疯狂重试（浪费资源、
 * 可能被通道限流），要么对网络抖动直接失败（白白损失成功率）。
 *
 * <p>支付系统的通道成功率每提升 0.1% 都是真金白银，这个区分直接值钱。
 */
public class ChannelInfrastructureException extends PaymentException {

    /** 是否值得重试。证书错误、参数错误这类不重试；超时、限流、5xx 可重试 */
    private final boolean retryable;

    public ChannelInfrastructureException(String message, boolean retryable) {
        super("CHANNEL_INFRA_ERROR", message);
        this.retryable = retryable;
    }

    public ChannelInfrastructureException(String message, boolean retryable, Throwable cause) {
        super("CHANNEL_INFRA_ERROR", message, cause);
        this.retryable = retryable;
    }

    public boolean isRetryable() { return retryable; }
}
'''

FILES["payment-shared-kernel/src/main/java/com/demo/payment/shared/exception/IdempotencyConflictException.java"] = r'''
package com.demo.payment.shared.exception;

/**
 * 幂等冲突：同一个幂等键，携带了不同的业务参数。
 *
 * <p>这是<b>客户端 bug 的信号</b>，必须暴露而不是容错。
 * 典型场景：前端重试时把金额从 100 改成了 200 却复用了同一个幂等键。
 * 若系统"善意地"按新参数处理，就会造成用户预期与实际扣款不一致。
 */
public class IdempotencyConflictException extends PaymentException {

    private final String idempotencyKey;

    public IdempotencyConflictException(String idempotencyKey, String message) {
        super("IDEMPOTENCY_CONFLICT", message);
        this.idempotencyKey = idempotencyKey;
    }

    public String idempotencyKey() { return idempotencyKey; }
}
'''

FILES["payment-shared-kernel/src/main/java/com/demo/payment/shared/util/IdGenerator.java"] = r'''
package com.demo.payment.shared.util;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ID 生成器。
 *
 * <p><b>支付系统对单号的特殊要求：</b>
 * <ul>
 *   <li><b>全局唯一</b>：outTradeNo 在通道侧唯一，重复会直接导致下单失败或串单。</li>
 *   <li><b>不可猜测</b>：订单号暴露在 URL 里，可被遍历就是信息泄露。
 *       纯自增 ID 会让竞争对手通过订单号推算你的日交易量。</li>
 *   <li><b>含时间前缀</b>：便于按时间范围分库分表、排查问题、DBA 做分区裁剪。</li>
 *   <li><b>长度可控</b>：微信 out_trade_no 限 32 位，支付宝限 64 位，需留足余量。</li>
 * </ul>
 *
 * <p>本实现采用「时间戳 + 序列 + 随机数」组合，单机可用；
 * 生产环境建议替换为号段模式（Leaf / TinyID），避免多机时钟回拨问题。
 */
public final class IdGenerator {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);
    private static final AtomicLong SEQUENCE = new AtomicLong(0);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long MAX_SEQUENCE = 999_999L;

    private IdGenerator() {}

    /** 支付单号：P + 17位时间戳 + 6位序列 + 4位随机 = 28 位 */
    public static String paymentOrderId() {
        return "P" + TS.format(Instant.now()) + seq() + rand(4);
    }

    /**
     * 发往通道的订单号。
     *
     * <p><b>关键设计：同一支付单多次尝试必须生成不同的 outTradeNo。</b>
     * 微信/支付宝的 out_trade_no 是全局唯一的，若重试时复用同一个号，
     * 第二次下单会返回"订单已存在"，导致重试永远失败。
     * 因此这里把 attemptSeq 编进单号，天然保证唯一。
     */
    public static String outTradeNo(String paymentOrderId, int attemptSeq) {
        return paymentOrderId + "A" + attemptSeq;
    }

    /** 退款单号：R + 时间戳 + 序列 + 随机 */
    public static String refundOrderId() {
        return "R" + TS.format(Instant.now()) + seq() + rand(4);
    }

    /** 幂等键（客户端未提供时服务端生成） */
    public static String idempotencyKey() {
        return "IK" + TS.format(Instant.now()) + rand(8);
    }

    private static String seq() {
        return String.format("%06d", SEQUENCE.updateAndGet(v -> v >= MAX_SEQUENCE ? 1 : v + 1));
    }

    private static String rand(int digits) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < digits; i++) {
            sb.append(RANDOM.nextInt(10));
        }
        return sb.toString();
    }
}
'''

# ============ domain: 值对象与实体 ============
FILES["payment-domain/src/main/java/com/demo/payment/domain/acquiring/model/aggregate/PaymentOrderId.java"] = r'''
package com.demo.payment.domain.acquiring.model.aggregate;

import com.demo.payment.shared.util.IdGenerator;

import java.util.Objects;

/**
 * 支付单 ID 值对象。
 *
 * <p><b>为什么不用裸 String？</b>
 * 支付系统里 ID 满天飞：paymentOrderId / merchantOrderNo / outTradeNo /
 * channelTransactionId / refundNo。如果全用 String，方法签名
 * {@code void refund(String a, String b)} 传错参数顺序，编译器不会报错，
 * 上线就把 A 商户的钱退给了 B 订单。<b>用类型包装是这个 bug 的唯一根治手段。</b>
 */
public final class PaymentOrderId {

    private final String value;

    private PaymentOrderId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("paymentOrderId must not be blank");
        }
        this.value = value;
    }

    public static PaymentOrderId newId() { return new PaymentOrderId(IdGenerator.paymentOrderId()); }
    public static PaymentOrderId of(String value) { return new PaymentOrderId(value); }

    public String value() { return value; }

    @Override
    public boolean equals(Object o) {
        if (this == o) { return true; }
        if (!(o instanceof PaymentOrderId other)) { return false; }
        return value.equals(other.value);
    }

    @Override
    public int hashCode() { return value.hashCode(); }

    @Override
    public String toString() { return value; }
}
'''

FILES["payment-domain/src/main/java/com/demo/payment/domain/acquiring/model/vo/OutTradeNo.java"] = r'''
package com.demo.payment.domain.acquiring.model.vo;

import java.util.Objects;

/**
 * 发往通道的订单号值对象。
 *
 * <p><b>这是支付系统里最容易混淆、也最容易出事故的一个概念。</b>
 * 一笔支付至少涉及三层单号，必须严格区分：
 * <pre>
 *   merchantOrderNo      商户系统的订单号（商户自己生成，可能重复投递）
 *   paymentOrderId       本支付平台的订单号（平台生成，全局唯一）
 *   outTradeNo           发往具体通道的订单号（每次通道尝试一个，绝不能复用）
 *   channelTransactionId 通道侧返回的流水号（如微信 transaction_id、Stripe pi_xxx）
 * </pre>
 *
 * <p>混淆 outTradeNo 和 paymentOrderId 是新手最常见的错误：
 * 直接拿 paymentOrderId 去当 outTradeNo，结果一切换通道重试就撞号，
 * 通道返回"订单已存在"，重试逻辑形同虚设。
 */
public final class OutTradeNo {

    /** 微信 out_trade_no 长度上限 */
    public static final int WECHAT_MAX_LENGTH = 32;
    /** 支付宝 out_trade_no 长度上限 */
    public static final int ALIPAY_MAX_LENGTH = 64;

    private final String value;

    private OutTradeNo(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("outTradeNo must not be blank");
        }
        this.value = value;
    }

    public static OutTradeNo of(String value) { return new OutTradeNo(value); }

    public String value() { return value; }

    /** 校验是否满足指定通道的长度约束，避免下单时才被通道打回 */
    public boolean lengthFits(int maxLength) { return value.length() <= maxLength; }

    @Override
    public boolean equals(Object o) {
        if (this == o) { return true; }
        if (!(o instanceof OutTradeNo other)) { return false; }
        return value.equals(other.value);
    }

    @Override
    public int hashCode() { return value.hashCode(); }

    @Override
    public String toString() { return value; }
}
'''

FILES["payment-domain/src/main/java/com/demo/payment/domain/acquiring/model/entity/PaymentAttempt.java"] = r'''
package com.demo.payment.domain.acquiring.model.entity;

import com.demo.payment.domain.acquiring.model.aggregate.PaymentOrderId;
import com.demo.payment.domain.acquiring.model.vo.OutTradeNo;
import com.demo.payment.domain.channel.model.ChannelCode;
import com.demo.payment.shared.money.Money;

import java.time.Instant;
import java.util.Objects;

/**
 * 支付尝试实体 —— 隶属于 PaymentOrder 聚合。
 *
 * <p>存在意义：一次支付可能经过多个通道（微信失败 → 切支付宝 → 再切银联）。
 * 每次尝试都要留下完整记录，否则：
 * <ul>
 *   <li>排查问题时只能看到"最终失败"，看不到中间在哪个通道、以什么错误码失败；</li>
 *   <li>通道成功率统计无从做起（这是智能路由的数据基础）；</li>
 *   <li>回调到达时无法判断它对应哪次尝试，可能用旧尝试的结果覆盖新尝试。</li>
 * </ul>
 *
 * <p><b>为什么是实体而非值对象：</b>它有生命周期（发起 → 有结果），需要被单独标识和更新，
 * 但它的标识只在聚合内有效（用 sequence 序号），所以是<b>局部实体</b>。
 */
public class PaymentAttempt {

    /** 尝试序号，从 1 开始，用于生成唯一的 outTradeNo */
    private final int sequence;
    private final PaymentOrderId orderId;
    private final ChannelCode channelCode;
    private final OutTradeNo outTradeNo;
    private final Money amount;
    private final Instant startedAt;

    private AttemptStatus status;
    private String channelTransactionId;
    private String channelRawStatus;
    private String failReason;
    private Instant finishedAt;

    private PaymentAttempt(int sequence, PaymentOrderId orderId, ChannelCode channelCode,
                           OutTradeNo outTradeNo, Money amount) {
        this.sequence = sequence;
        this.orderId = orderId;
        this.channelCode = channelCode;
        this.outTradeNo = outTradeNo;
        this.amount = amount;
        this.startedAt = Instant.now();
        this.status = AttemptStatus.INIT;
    }

    public static PaymentAttempt start(int sequence, PaymentOrderId orderId, ChannelCode channelCode,
                                       OutTradeNo outTradeNo, Money amount) {
        return new PaymentAttempt(sequence, orderId, channelCode, outTradeNo, amount);
    }

    public void markResult(boolean success, String channelTransactionId,
                           String channelRawStatus, Instant occurredAt) {
        this.channelTransactionId = channelTransactionId;
        this.channelRawStatus = channelRawStatus;
        this.status = success ? AttemptStatus.SUCCEEDED : AttemptStatus.FAILED;
        this.finishedAt = occurredAt != null ? occurredAt : Instant.now();
    }

    /** 标记为已授权（两段式通道的授权成功，尚未请款） */
    public void markAuthorized(String channelTransactionId, Instant occurredAt) {
        this.channelTransactionId = channelTransactionId;
        this.channelRawStatus = "AUTHORIZED";
        this.status = AttemptStatus.AUTHORIZED;
        this.finishedAt = occurredAt;
    }

    public void markFailed(String reason) {
        this.status = AttemptStatus.FAILED;
        this.failReason = reason;
        this.finishedAt = Instant.now();
    }

    public boolean isAuthorized() { return status == AttemptStatus.AUTHORIZED; }
    public boolean isSucceeded() { return status == AttemptStatus.SUCCEEDED; }
    public boolean isFinished() {
        return status == AttemptStatus.SUCCEEDED || status == AttemptStatus.FAILED;
    }

    public int sequence() { return sequence; }
    public PaymentOrderId orderId() { return orderId; }
    public ChannelCode channelCode() { return channelCode; }
    public OutTradeNo outTradeNo() { return outTradeNo; }
    public Money amount() { return amount; }
    public Instant startedAt() { return startedAt; }
    public AttemptStatus status() { return status; }
    public String channelTransactionId() { return channelTransactionId; }
    public String channelRawStatus() { return channelRawStatus; }
    public String failReason() { return failReason; }
    public Instant finishedAt() { return finishedAt; }

    /** 尝试状态 */
    public enum AttemptStatus {
        /** 已创建，尚未收到通道结果 */
        INIT,
        /** 已授权（冻结额度，未请款） */
        AUTHORIZED,
        /** 支付成功 */
        SUCCEEDED,
        /** 支付失败 */
        FAILED,
        /** 已关闭 */
        CLOSED
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) { return true; }
        if (!(o instanceof PaymentAttempt other)) { return false; }
        return sequence == other.sequence && outTradeNo.equals(other.outTradeNo);
    }

    @Override
    public int hashCode() { return Objects.hash(sequence, outTradeNo); }

    @Override
    public String toString() {
        return "PaymentAttempt{seq=" + sequence + ", channel=" + channelCode
                + ", outTradeNo=" + outTradeNo + ", status=" + status + "}";
    }
}
'''

FILES["payment-domain/src/main/java/com/demo/payment/domain/acquiring/model/entity/RefundOrder.java"] = r'''
package com.demo.payment.domain.acquiring.model.entity;

import com.demo.payment.domain.acquiring.model.aggregate.PaymentOrderId;
import com.demo.payment.shared.money.Money;

import java.time.Instant;

/**
 * 退款单 —— 隶属于 PaymentOrder 聚合（局部实体）。
 *
 * <p><b>关键设计：退款金额校验由聚合根 PaymentOrder 统一负责。</b>
 * 本实体自身不校验"是否超额"，因为那样做在并发下是无效的：
 * 两个线程同时读取"已退 0"，各自校验通过，同时写入，结果超额退款。
 * 必须由聚合根在同一把锁内完成"读-校验-写"。
 *
 * <p>这也正是退款不能独立成聚合的根本原因。
 */
public class RefundOrder {

    private final String refundNo;
    private final PaymentOrderId orderId;
    private final Money amount;
    private final String reason;
    private final Instant createdAt;

    private RefundStatus status;
    private String channelRefundId;
    private String failReason;
    private Instant finishedAt;

    private RefundOrder(String refundNo, PaymentOrderId orderId, Money amount,
                        String reason, RefundStatus status) {
        this.refundNo = refundNo;
        this.orderId = orderId;
        this.amount = amount;
        this.reason = reason;
        this.status = status;
        this.createdAt = Instant.now();
    }

    public static RefundOrder create(String refundNo, PaymentOrderId orderId,
                                     Money amount, String reason) {
        return new RefundOrder(refundNo, orderId, amount, reason, RefundStatus.PENDING);
    }

    public void markProcessing() { this.status = RefundStatus.PROCESSING; }

    public void markSucceeded(String channelRefundId, Instant finishedAt) {
        this.status = RefundStatus.SUCCEEDED;
        this.channelRefundId = channelRefundId;
        this.finishedAt = finishedAt != null ? finishedAt : Instant.now();
    }

    public void markFailed(String reason) {
        this.status = RefundStatus.FAILED;
        this.failReason = reason;
        this.finishedAt = Instant.now();
    }

    /**
     * 是否计入"已退款"限额。
     *
     * <p><b>FAILED 的退款不计入</b>：否则一次失败退款会永久占用退款额度，
     * 导致后续无法退款。但 <b>PENDING / PROCESSING 必须计入</b>，
     * 因为通道可能稍后成功 —— 这是防止并发超额退款的关键保守策略。
     */
    public boolean countsTowardLimit() {
        return status == RefundStatus.SUCCEEDED
                || status == RefundStatus.PENDING
                || status == RefundStatus.PROCESSING;
    }

    public String refundNo() { return refundNo; }
    public PaymentOrderId orderId() { return orderId; }
    public Money amount() { return amount; }
    public String reason() { return reason; }
    public Instant createdAt() { return createdAt; }
    public RefundStatus status() { return status; }
    public String channelRefundId() { return channelRefundId; }
    public String failReason() { return failReason; }
    public Instant finishedAt() { return finishedAt; }

    public enum RefundStatus {
        /** 待处理（已占用退款额度） */
        PENDING,
        /** 处理中（已提交通道） */
        PROCESSING,
        SUCCEEDED,
        FAILED
    }

    @Override
    public String toString() {
        return "RefundOrder{refundNo='" + refundNo + "', amount=" + amount
                + ", status=" + status + "}";
    }
}
'''

# ============ domain: 领域事件 ============
EVENTS = {
    "PaymentOrderCreated": ("支付单已创建", "String merchantId, String merchantOrderNo, Money amount, PaymentMethodType paymentMethod",
        "this.merchantId = merchantId;\n        this.merchantOrderNo = merchantOrderNo;\n        this.amount = amount;\n        this.paymentMethod = paymentMethod;"),
    "PaymentSucceeded": ("支付成功", "String merchantOrderNo, String outTradeNo, ChannelCode channelCode, String channelTransactionId, Money amount",
        "this.merchantOrderNo = merchantOrderNo;\n        this.outTradeNo = outTradeNo;\n        this.channelCode = channelCode;\n        this.channelTransactionId = channelTransactionId;\n        this.amount = amount;"),
    "PaymentFailed": ("支付失败（所有尝试均已失败）", "String merchantOrderNo, String reason",
        "this.merchantOrderNo = merchantOrderNo;\n        this.reason = reason;"),
    "PaymentAttemptFailed": ("单次通道尝试失败", "String merchantOrderNo, String outTradeNo, int attemptSeq, ChannelCode channelCode",
        "this.merchantOrderNo = merchantOrderNo;\n        this.outTradeNo = outTradeNo;\n        this.attemptSeq = attemptSeq;\n        this.channelCode = channelCode;"),
    "PaymentCaptured": ("请款成功（两段式第二步完成）", "String merchantOrderNo, String channelTransactionId",
        "this.merchantOrderNo = merchantOrderNo;\n        this.channelTransactionId = channelTransactionId;"),
    "PaymentClosed": ("支付单已关闭", "String merchantOrderNo, String reason",
        "this.merchantOrderNo = merchantOrderNo;\n        this.reason = reason;"),
    "RefundRequested": ("退款已受理", "String merchantOrderNo, String refundNo, Money amount, String reason",
        "this.merchantOrderNo = merchantOrderNo;\n        this.refundNo = refundNo;\n        this.amount = amount;\n        this.reason = reason;"),
}

for name, (desc, extra_fields, assign) in EVENTS.items():
    getter_lines = []
    for f in extra_fields.split(", "):
        fname = f.split(" ")[1]
        ftype = f.split(" ")[0]
        getter_lines.append("    public %s %s() { return %s; }" % (ftype, fname, fname))
    getters = "\n".join(getter_lines)

    FILES["payment-domain/src/main/java/com/demo/payment/domain/acquiring/event/%s.java" % name] = r'''
package com.demo.payment.domain.acquiring.event;

import com.demo.payment.domain.channel.model.ChannelCode;
import com.demo.payment.domain.channel.model.PaymentMethodType;
import com.demo.payment.shared.event.DomainEvent;
import com.demo.payment.shared.money.Money;

import java.time.Instant;

/**
 * %s —— 领域事件。
 *
 * <p>事件是"已发生的事实"，因此字段不可变（record）。
 * 消费方（结算、账务、风控、商户通知）订阅此事件做后续处理，
 * 支付主链路不感知它们的存在。
 */
public record %s(
        String aggregateId,
        %s,
        Instant occurredAt
) implements DomainEvent {

    public %s(String aggregateId, %s, Instant occurredAt) {
        this.aggregateId = aggregateId;
        %s
        this.occurredAt = occurredAt;
    }

%s
}
''' % (desc, name, extra_fields, name, extra_fields, assign, getters)

# ============ domain: 仓储接口 ============
FILES["payment-domain/src/main/java/com/demo/payment/domain/acquiring/repository/PaymentOrderRepository.java"] = r'''
package com.demo.payment.domain.acquiring.repository;

import com.demo.payment.domain.acquiring.model.aggregate.PaymentOrder;
import com.demo.payment.domain.acquiring.model.aggregate.PaymentOrderId;
import com.demo.payment.domain.acquiring.model.vo.OutTradeNo;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.Lock;

/**
 * 支付单仓储接口（domain 层定义，infrastructure 层实现）。
 *
 * <p><b>为什么仓储接口要放在 domain 层？</b>
 * 这是 DDD 的经典争议点。放 domain 层的理由是：仓储操作的语义
 * （"按商户订单号查找"、"保存聚合"）是领域概念，不是技术概念。
 * 接口放这里，领域层才能在不引入任何持久化框架的前提下表达持久化需求。
 * 实现（MyBatis / JPA / 内存 Map）放 infrastructure，运行时注入。
 *
 * <p><b>关于 {@code obtainLock}：</b>
 * 支付单是高并发写对象，回调、查证补偿、关单定时任务可能同时到达同一笔单。
 * 仓储必须提供获取分布式锁的能力，由应用层显式加锁。
 * 把锁藏在 save() 内部是错的 —— 那会让"读-改-写"的边界变得不可见。
 */
public interface PaymentOrderRepository {

    Optional<PaymentOrder> findById(PaymentOrderId id);

    /**
     * 按商户订单号 + 商户号查找。
     *
     * <p><b>必须带商户号</b>：只用 merchantOrderNo 查询存在跨商户数据泄露风险。
     * 唯一索引也必须是 (merchant_id, merchant_order_no) 联合唯一，
     * 因为不同商户完全可能使用相同的订单号（比如都叫 "ORDER001"）。
     */
    Optional<PaymentOrder> findByMerchantOrderNo(String merchantId, String merchantOrderNo);

    /** 按通道订单号反查（回调通知到达时使用） */
    Optional<PaymentOrder> findByOutTradeNo(OutTradeNo outTradeNo);

    /**
     * 保存聚合。
     *
     * <p><b>实现必须做乐观锁</b>：UPDATE ... WHERE id = ? AND version = ?，
     * 影响行数为 0 说明有并发写入，必须抛出并发异常让上层重试，
     * 绝不能无条件覆盖 —— 否则后到的错误结果会覆盖先到的正确结果。
     */
    void save(PaymentOrder order);

    /** 扫描处于处理中且已超时的订单，用于查证补偿与自动关单 */
    List<PaymentOrder> findTimeoutCandidates(int limitMinutes, int limit);

    /**
     * 获取该订单的分布式锁。
     *
     * @return 锁对象，调用方必须在 finally 中释放
     */
    Lock obtainLock(PaymentOrderId id);
}
'''

# ============ domain: 领域服务 ============
FILES["payment-domain/src/main/java/com/demo/payment/domain/acquiring/service/RefundPolicyService.java"] = r'''
package com.demo.payment.domain.acquiring.service;

import com.demo.payment.domain.acquiring.model.aggregate.PaymentOrder;
import com.demo.payment.domain.channel.model.ChannelCapability;
import com.demo.payment.shared.money.Money;

/**
 * 退款策略领域服务。
 *
 * <p><b>什么时候该用领域服务，而不是把逻辑塞进聚合根？</b>
 * 判断标准：这段逻辑是否<b>只依赖聚合内部状态</b>。
 * 退款有效性校验需要同时看「订单状态 + 通道能力（是否支持部分退款、退款期限）」，
 * 后者不属于聚合，因此提成领域服务，由应用层把能力作为参数传入。
 * 这样领域服务依然保持纯净（无外部依赖），可零 mock 测试。
 */
public interface RefundPolicyService {

    /**
     * 校验本次退款是否被允许。
     *
     * @param order      支付单
     * @param capability 该订单所用通道的能力矩阵
     * @param amount     本次退款金额
     * @return 校验结果，含拒绝原因
     */
    RefundCheckResult check(PaymentOrder order, ChannelCapability capability, Money amount);
}
'''

FILES["payment-domain/src/main/java/com/demo/payment/domain/acquiring/service/RefundCheckResult.java"] = r'''
package com.demo.payment.domain.acquiring.service;

/**
 * 退款校验结果。用结果对象而非抛异常，便于批量校验场景收集所有问题。
 */
public record RefundCheckResult(boolean allowed, String rejectReason) {

    private static final RefundCheckResult OK = new RefundCheckResult(true, null);

    public static RefundCheckResult ok() { return OK; }
    public static RefundCheckResult reject(String reason) { return new RefundCheckResult(false, reason); }
}
'''

FILES["payment-domain/src/main/java/com/demo/payment/domain/acquiring/service/RefundPolicyServiceImpl.java"] = r'''
package com.demo.payment.domain.acquiring.service;

import com.demo.payment.domain.acquiring.model.aggregate.PaymentOrder;
import com.demo.payment.domain.channel.model.ChannelCapability;
import com.demo.payment.shared.money.Money;

import java.time.Duration;
import java.time.Instant;

/**
 * 退款策略默认实现。
 *
 * <p>这里集中了退款的全部前置校验规则。把它们集中在一处（而不是散落在 Controller、
 * Service、Adapter 各处）的价值在于：<b>规则是可见、可测、可演进的</b>。
 */
public class RefundPolicyServiceImpl implements RefundPolicyService {

    @Override
    public RefundCheckResult check(PaymentOrder order, ChannelCapability capability, Money amount) {
        // 规则一：订单必须已支付
        if (!order.status().isPaid()) {
            return RefundCheckResult.reject("订单未支付成功，当前状态：" + order.status());
        }

        // 规则二：金额必须为正且不超过原单金额
        if (!amount.isPositive()) {
            return RefundCheckResult.reject("退款金额必须大于 0");
        }
        if (amount.isGreaterThan(order.amount())) {
            return RefundCheckResult.reject("退款金额超过原订单金额");
        }

        // 规则三：币种必须一致（跨币种退款涉及汇率，属于另一个业务域）
        if (!amount.currency().equals(order.amount().currency())) {
            return RefundCheckResult.reject("退款币种与原订单不一致");
        }

        // 规则四：部分退款能力
        boolean isPartial = amount.isLessThan(order.amount());
        if (isPartial && !capability.supportsPartialRefund()) {
            return RefundCheckResult.reject("通道 " + capability.channelCode() + " 不支持部分退款");
        }

        // 规则五：多次部分退款能力
        boolean hasPreviousRefund = !order.totalRefunded().isZero();
        if (isPartial && hasPreviousRefund && !capability.supportsMultiplePartialRefund()) {
            return RefundCheckResult.reject("通道 " + capability.channelCode() + " 不支持多次部分退款");
        }

        // 规则六：退款期限（通道能力决定，Antom 的 BNPL 类只有 90~120 天）
        Integer window = capability.refundWindowDays();
        if (window != null) {
            long days = Duration.between(order.createdAt(), Instant.now()).toDays();
            if (!capability.isRefundableAfterDays((int) days)) {
                return RefundCheckResult.reject("超出通道退款期限：" + days + " 天 > " + window + " 天，需走人工差错流程");
            }
        }

        // 规则七：累计不超额（最终防线，与聚合内的校验形成双保险）
        if (order.totalRefunded().plus(amount).isGreaterThan(order.amount())) {
            return RefundCheckResult.reject("累计退款将超过原订单金额，已退："
                    + order.totalRefunded() + "，本次：" + amount);
        }

        return RefundCheckResult.ok();
    }
}
'''

for path, content in FILES.items():
    full = os.path.join(BASE, path)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    with open(full, "w", encoding="utf-8") as f:
        f.write(content.lstrip("\n"))
    print("WROTE", path)

print("\nTOTAL:", len(FILES))
