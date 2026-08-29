package com.demo.payment.domain.acquiring.model.aggregate;

import com.demo.payment.domain.acquiring.event.*;
import com.demo.payment.domain.acquiring.model.entity.PaymentAttempt;
import com.demo.payment.domain.acquiring.model.entity.RefundOrder;
import com.demo.payment.domain.acquiring.model.vo.OutTradeNo;
import com.demo.payment.domain.acquiring.statemachine.PaymentStateMachine;
import com.demo.payment.domain.acquiring.statemachine.PaymentStatus;
import com.demo.payment.domain.channel.model.ChannelCode;
import com.demo.payment.domain.channel.model.PaymentMethodType;
import com.demo.payment.shared.event.DomainEvent;
import com.demo.payment.shared.money.Money;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 支付单聚合根。
 *
 * <h3>聚合边界的三个关键决策</h3>
 *
 * <p><b>决策一：退款为什么在聚合内，而不是独立聚合？</b><br>
 * 常见做法是退款单独立成聚合，理由是"退款生命周期独立"。
 * 但退款有一条铁律：<b>累计退款额不得超过原支付额</b>。
 * 这是硬性的资金安全约束，必须强一致。若拆成两个聚合，
 * 两笔并发退款各自读到"已退 0"，各自校验通过，同时写入 —— 直接超额退款，产生资损。
 * <br>DDD 的聚合划分原则第一条就是"<b>在一致性边界内建模真正的不变量</b>"。
 * 超额退款是真正的不变量，所以退款必须在聚合内，由聚合根加锁串行校验。
 *
 * <p><b>决策二：为什么有 PaymentAttempt（支付尝试）这一层实体？</b><br>
 * 一次支付可能尝试多个通道：微信失败 → 切支付宝 → 再切银联。
 * 每次尝试的 outTradeNo 必须<b>不同</b>（微信/支付宝的 out_trade_no 全局唯一，
 * 复用会导致第二次下单直接返回"订单已存在"）。
 * 如果只有一层 PaymentOrder，就无法表达"同一笔订单在第 2 次尝试的第 3 个通道上失败了"，
 * 排查问题时只能看到最终结果，看不到过程。
 *
 * <p><b>决策三：聚合不依赖任何外部服务。</b><br>
 * 聚合只做纯内存状态变更，不发 HTTP、不查 DB、不注入任何 Port。
 * 调用通道是应用层的职责（见 PaymentCommandService）。
 * 这样聚合可以零 mock 单测 —— 对一个资金系统，这是巨大的可测性收益。
 */
public class PaymentOrder {

    private final PaymentOrderId id;
    private final String merchantId;
    private final String merchantOrderNo;
    private final Money amount;
    private final PaymentMethodType paymentMethod;
    private final String subject;
    private final String notifyUrl;
    private final Instant expireAt;
    private final Instant createdAt;

    /** 当前状态 */
    private PaymentStatus status;

    /** 通道尝试记录，至少有一条 */
    private final List<PaymentAttempt> attempts = new ArrayList<>();

    /** 退款记录（聚合内实体，保证累计退款不超额） */
    private final List<RefundOrder> refunds = new ArrayList<>();

    /** 聚合内累积的领域事件，由应用层在事务提交后发布 */
    private final List<DomainEvent> domainEvents = new ArrayList<>();

    /**
     * 乐观锁版本号。
     * 支付单是高并发写对象（回调、查证补偿、关单任务可能并发到达），
     * 必须靠版本号做乐观锁，否则后写入会覆盖先写入的正确结果。
     */
    private long version;

    private Instant lastModifiedAt;

    // ==================== 工厂方法 ====================

    /**
     * @param createdAt 创建时间。<b>必须由外部传入而不能在构造函数里取 Instant.now()</b>，
     *                  否则从数据库重建的历史订单，其创建时间会被替换成当前时间，
     *                  导致所有依赖账龄的校验（退款期限、结算周期、时效统计）全部失效。
     *                  这是一个非常隐蔽的缺陷：单测全绿、功能自测正常，
     *                  直到第一批"超期退款"请求到来时才暴露。
     */
    private PaymentOrder(PaymentOrderId id, String merchantId, String merchantOrderNo,
                         Money amount, PaymentMethodType paymentMethod, String subject,
                         String notifyUrl, Instant expireAt, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.merchantId = Objects.requireNonNull(merchantId, "merchantId");
        this.merchantOrderNo = Objects.requireNonNull(merchantOrderNo, "merchantOrderNo");
        this.amount = Objects.requireNonNull(amount, "amount");
        this.paymentMethod = Objects.requireNonNull(paymentMethod, "paymentMethod");
        this.subject = subject;
        this.notifyUrl = notifyUrl;
        this.expireAt = expireAt;
        this.status = PaymentStatus.CREATED;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.lastModifiedAt = this.createdAt;
        this.version = 0L;
    }

    /**
     * 创建支付单。
     *
     * <p><b>为什么用工厂方法而不是构造函数？</b>
     * 构造函数无法表达业务语义，也无法在创建时就登记领域事件。
     * 工厂方法让"创建支付单"这件事在代码里是可读的一句话，
     * 且能强制校验入参、统一生成 ID、自动注册 PaymentOrderCreated 事件。
     */
    public static PaymentOrder create(String merchantId, String merchantOrderNo, Money amount,
                                      PaymentMethodType paymentMethod, String subject,
                                      String notifyUrl, Instant expireAt) {
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("Payment amount must be positive: " + amount);
        }
        if (expireAt != null && !expireAt.isAfter(Instant.now())) {
            throw new IllegalArgumentException("expireAt must be in the future: " + expireAt);
        }
        Instant now = Instant.now();
        PaymentOrder order = new PaymentOrder(
                PaymentOrderId.newId(), merchantId, merchantOrderNo, amount,
                paymentMethod, subject, notifyUrl, expireAt, now);
        order.registerEvent(new PaymentOrderCreated(
                order.id.value(), merchantId, merchantOrderNo, amount, paymentMethod, now));
        return order;
    }

    /** 从持久化重建（仓储专用，不产生领域事件） */
    public static PaymentOrder reconstitute(PaymentOrderId id, String merchantId, String merchantOrderNo,
                                            Money amount, PaymentMethodType paymentMethod, String subject,
                                            String notifyUrl, Instant expireAt, PaymentStatus status,
                                            List<PaymentAttempt> attempts, List<RefundOrder> refunds,
                                            long version, Instant createdAt, Instant lastModifiedAt) {
        PaymentOrder order = new PaymentOrder(id, merchantId, merchantOrderNo, amount,
                paymentMethod, subject, notifyUrl, expireAt, createdAt);
        order.status = status;
        order.attempts.addAll(attempts);
        order.refunds.addAll(refunds);
        order.version = version;
        order.lastModifiedAt = lastModifiedAt;
        return order;
    }

    // ==================== 状态变更（全部经过状态机校验） ====================

    /**
     * 登记一次通道尝试，生成该次尝试专用的通道订单号。
     *
     * @param channelCode 本次使用的通道
     * @param outTradeNo  发往通道的订单号，<b>每次尝试必须不同</b>
     */
    public PaymentAttempt startAttempt(ChannelCode channelCode, OutTradeNo outTradeNo) {
        if (outTradeNo == null) {
            throw new IllegalArgumentException("outTradeNo is required");
        }
        if (attempts.stream().anyMatch(a -> a.outTradeNo().equals(outTradeNo))) {
            throw new IllegalStateException("Duplicate outTradeNo in same order: " + outTradeNo);
        }
        int nextSeq = attempts.size() + 1;
        PaymentAttempt attempt = PaymentAttempt.start(nextSeq, id, channelCode, outTradeNo, amount);
        attempts.add(attempt);

        if (status == PaymentStatus.CREATED) {
            transitTo(PaymentStatus.PAYING);
        }
        touch();
        return attempt;
    }

    /**
     * 应用通道返回的支付结果 —— <b>整个聚合最危险的方法</b>。
     *
     * <p>它必须同时防住三件事：
     * <ol>
     *   <li><b>回调乱序</b>：终态拒绝变更（由状态机 isTerminal 保证）。</li>
     *   <li><b>金额篡改</b>：通道返回的金额必须与订单金额一致，否则视为异常并告警。
     *       这是支付领域最常见的安全检查缺失点 —— 攻击者篡改回调报文里的金额，
     *       若系统只改状态不校验金额，1 分钱就能买走 1000 元的商品。</li>
     *   <li><b>订单号不匹配</b>：回调里的 outTradeNo 必须属于本订单。</li>
     * </ol>
     *
     * @return 是否发生了真实的状态变更（false 表示幂等重复通知，调用方无需后续处理）
     */
    public boolean applyChannelResult(OutTradeNo outTradeNo, boolean success,
                                      Money channelAmount, String channelTransactionId,
                                      String channelRawStatus, Instant occurredAt) {
        PaymentAttempt attempt = findAttempt(outTradeNo)
                .orElseThrow(() -> new IllegalStateException(
                        "outTradeNo does not belong to this order: " + outTradeNo));

        // 安全检查一：金额必须一致
        if (success && channelAmount != null && !channelAmount.equals(this.amount)) {
            throw new IllegalStateException(
                    "Amount mismatch! expected=" + this.amount + " actual=" + channelAmount
                            + " orderId=" + id.value() + " —— possible tampered notification");
        }

        attempt.markResult(success, channelTransactionId, channelRawStatus, occurredAt);

        if (!success) {
            /*
             * 订单已支付成功，却又收到失败结果 —— 这是典型的异常，来源通常有两种：
             *   1. 回调乱序：成功的回调先到，失败的补偿推送后到
             *   2. 通道数据异常：同一笔交易给出了矛盾的终态
             *
             * 处理方式刻意选择"拒绝变更 + 发告警事件"，而不是抛异常：
             *   - 抛异常会让回调接入层返回 5xx，通道判定通知失败并持续重投，
             *     形成"越错越投、越投越错"的循环，日志里全是重复告警；
             *   - 返回 false 表示"未产生状态变更"，接入层照常回成功应答让通道停止重投，
             *     同时由告警事件触发人工核查。
             *
             * 这是资金系统的一条通用原则：对可疑行为要"记录并放行通知，拒绝并保留证据"，
             * 而不是用异常把问题掩盖成一次通道重试。
             */
            if (status.isPaid()) {
                registerEvent(new SuspiciousNotificationReceived(
                        id.value(), merchantOrderNo, outTradeNo.value(),
                        "PAID_ORDER_RECEIVED_FAILURE",
                        "已支付订单收到失败结果，疑似回调乱序或通道数据异常，当前状态保持为 "
                                + status + "，请人工核查", occurredAt));
                touch();
                return false;
            }
            // 单次尝试失败不等于订单失败：可能还有其它通道可切换，由应用层决定是否重试
            registerEvent(new PaymentAttemptFailed(id.value(), merchantOrderNo, outTradeNo.value(),
                    attempt.sequence(), channelCodeOf(attempt), occurredAt));
            touch();
            return true;
        }

        PaymentStatus target = attempt.isAuthorized() ? PaymentStatus.AUTHORIZED : PaymentStatus.PAID;
        boolean changed = transitTo(target);
        if (changed) {
            registerEvent(new PaymentSucceeded(id.value(), merchantOrderNo, outTradeNo.value(),
                    channelCodeOf(attempt), channelTransactionId, amount, occurredAt));
        }
        touch();
        return changed;
    }

    /**
     * 申请退款。
     *
     * <p><b>超额退款防护</b>：由聚合根在同一把锁内完成"读取已退金额 → 校验 → 写入新退款单"，
     * 杜绝并发退款超额。这是把 RefundOrder 放进聚合内的全部意义所在。
     *
     * @param refundAmount 本次退款金额
     * @param reason       退款原因
     */
    public RefundOrder requestRefund(Money refundAmount, String reason, int refundWindowDays) {
        if (!status.isPaid()) {
            throw new IllegalStateException("Cannot refund, order not paid. status=" + status);
        }
        if (!refundAmount.currency().equals(amount.currency())) {
            throw new IllegalArgumentException("Refund currency mismatch: " + refundAmount.currency());
        }
        if (refundAmount.isZero() || refundAmount.isGreaterThan(amount)) {
            throw new IllegalArgumentException("Invalid refund amount: " + refundAmount);
        }

        // 核心不变量校验：累计退款 + 本次 <= 原金额
        Money alreadyRefunded = totalRefunded();
        if (alreadyRefunded.plus(refundAmount).isGreaterThan(amount)) {
            throw new IllegalStateException(
                    "Refund amount exceeds original amount. original=" + amount
                            + " refunded=" + alreadyRefunded + " requested=" + refundAmount);
        }

        /*
         * 退款有效期校验。期限由通道能力决定，差异极大：
         *   微信/支付宝 365 天、银联 180 天、Antom 的 BNPL 类 90~120 天。
         *
         * 注意：这里只依赖 refundWindowDays，不依赖 expireAt。
         * expireAt 是"支付单有效期"（用户多久之内必须付款），
         * 与"退款期限"（付款后多久之内可以退）是两个完全不同的概念。
         * 早期版本误把 expireAt 作为校验前置条件，导致未设置过期时间的订单
         * 会永久跳过退款期限校验 —— 这类"顺手加的条件"是典型的隐蔽缺陷，
         * 且往往在上线数月后、第一批超期退款请求到来时才暴露。
         *
         * refundWindowDays <= 0 表示无限制（如 Worldpay）。
         */
        if (refundWindowDays > 0) {
            long days = java.time.Duration.between(createdAt, Instant.now()).toDays();
            if (days > refundWindowDays) {
                throw new IllegalStateException(
                        "超出通道退款期限，需转人工差错流程. 已过天数=" + days
                                + " 通道期限=" + refundWindowDays + " 天");
            }
        }

        String refundNo = id.value() + "_R" + (refunds.size() + 1);
        RefundOrder refund = RefundOrder.create(refundNo, id, refundAmount, reason);
        refunds.add(refund);

        // 状态流转：全额退款 → REFUNDED；部分 → PARTIALLY_REFUNDED
        boolean full = alreadyRefunded.plus(refundAmount).equals(amount);
        transitTo(full ? PaymentStatus.REFUNDED : PaymentStatus.PARTIALLY_REFUNDED);

        registerEvent(new RefundRequested(id.value(), merchantOrderNo, refundNo, refundAmount, reason, Instant.now()));
        touch();
        return refund;
    }

    /** 关闭订单（超时未支付 / 用户主动取消） */
    public boolean close(String reason) {
        if (status.isPaid()) {
            // 已支付的订单不能关闭 —— 要关也得走退款，否则钱货两空
            throw new IllegalStateException("Cannot close a paid order, use refund instead. status=" + status);
        }
        boolean changed = transitTo(PaymentStatus.CLOSED);
        if (changed) {
            registerEvent(new PaymentClosed(id.value(), merchantOrderNo, reason, Instant.now()));
            touch();
        }
        return changed;
    }

    /** 标记支付失败（所有通道尝试均失败后由应用层调用） */
    public boolean markFailed(String reason) {
        boolean changed = transitTo(PaymentStatus.FAILED);
        if (changed) {
            registerEvent(new PaymentFailed(id.value(), merchantOrderNo, reason, Instant.now()));
            touch();
        }
        return changed;
    }

    /** 请款完成（两段式通道的第二步） */
    public boolean markCaptured(String channelTransactionId, Instant occurredAt) {
        boolean changed = transitTo(PaymentStatus.PAID);
        if (changed) {
            registerEvent(new PaymentCaptured(id.value(), merchantOrderNo, channelTransactionId, occurredAt));
            touch();
        }
        return changed;
    }

    // ==================== 内部方法 ====================

    private boolean transitTo(PaymentStatus target) {
        if (status == target) {
            return false; // 幂等：重复通知不做任何变更
        }
        PaymentStateMachine.validate(status, target);
        status = target;
        return true;
    }

    private void touch() {
        this.lastModifiedAt = Instant.now();
    }

    private ChannelCode channelCodeOf(PaymentAttempt attempt) {
        return attempt.channelCode();
    }

    private java.util.Optional<PaymentAttempt> findAttempt(OutTradeNo outTradeNo) {
        return attempts.stream().filter(a -> a.outTradeNo().equals(outTradeNo)).findFirst();
    }

    private void registerEvent(DomainEvent event) {
        this.domainEvents.add(event);
    }

    // ==================== 查询方法 ====================

    /** 累计已退款金额（含处理中的退款，避免并发超额） */
    public Money totalRefunded() {
        return refunds.stream()
                .filter(RefundOrder::countsTowardLimit)
                .map(RefundOrder::amount)
                .reduce(Money.ofMinor(0L, amount.currency()), Money::plus);
    }

    /** 剩余可退金额 */
    public Money refundableAmount() {
        return amount.minus(totalRefunded());
    }

    public boolean isExpired(Instant now) {
        return expireAt != null && now.isAfter(expireAt);
    }

    /** 当前生效的尝试（最后一次） */
    public PaymentAttempt currentAttempt() {
        if (attempts.isEmpty()) {
            return null;
        }
        return attempts.get(attempts.size() - 1);
    }

    /** 取出并清空领域事件（应用层在事务提交后发布） */
    public List<DomainEvent> pullDomainEvents() {
        List<DomainEvent> events = new ArrayList<>(domainEvents);
        domainEvents.clear();
        return events;
    }

    // ==================== Getter ====================

    public PaymentOrderId id() { return id; }
    public String merchantId() { return merchantId; }
    public String merchantOrderNo() { return merchantOrderNo; }
    public Money amount() { return amount; }
    public PaymentMethodType paymentMethod() { return paymentMethod; }
    public String subject() { return subject; }
    public String notifyUrl() { return notifyUrl; }
    public Instant expireAt() { return expireAt; }
    public Instant createdAt() { return createdAt; }
    public Instant lastModifiedAt() { return lastModifiedAt; }
    public PaymentStatus status() { return status; }
    public long version() { return version; }
    public List<PaymentAttempt> attempts() { return Collections.unmodifiableList(attempts); }
    public List<RefundOrder> refunds() { return Collections.unmodifiableList(refunds); }

    @Override
    public boolean equals(Object o) {
        if (this == o) { return true; }
        if (!(o instanceof PaymentOrder other)) { return false; }
        return id.equals(other.id);
    }

    @Override
    public int hashCode() { return id.hashCode(); }

    @Override
    public String toString() {
        return "PaymentOrder{id=" + id.value() + ", merchantOrderNo='" + merchantOrderNo
                + "', amount=" + amount + ", status=" + status + ", attempts=" + attempts.size() + "}";
    }
}
