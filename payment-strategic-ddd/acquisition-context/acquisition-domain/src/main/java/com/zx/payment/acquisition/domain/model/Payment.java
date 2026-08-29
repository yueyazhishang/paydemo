package com.zx.payment.acquisition.domain.model;

import com.zx.payment.acquisition.domain.event.PaymentClosedEvent;
import com.zx.payment.acquisition.domain.event.PaymentCreatedEvent;
import com.zx.payment.acquisition.domain.event.PaymentFailedEvent;
import com.zx.payment.acquisition.domain.event.PaymentSucceededEvent;
import com.zx.payment.shared.ChannelCode;
import com.zx.payment.shared.DomainEvent;
import com.zx.payment.shared.Money;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 聚合根：支付单（收单上下文的核心）。
 *
 * ==================== 守护的不变量 ====================
 *  1. 应付金额一旦创建不可变更；币种不可变更；
 *  2. 累计已收金额 ≤ 应付金额（超收直接抛异常，宁可人工介入也不能默默记账）；
 *  3. 同一时刻最多只有一个 active attempt（防止重复下单导致重复扣款）；
 *  4. 状态迁移必须符合状态机，非法迁移抛异常；
 *  5. 终态（SUCCESS / CLOSED）不可再迁移；
 *  6. 所有状态推进幂等：重复回调直接返回 false，不产生事件。
 *
 * ==================== 并发控制：状态机幂等 + 乐观锁，两者缺一不可 ====================
 *
 * 为什么不能只靠状态机幂等（v1 就是这么想的）：
 *   状态机检查的是"当前状态是否允许本次迁移"，但它挡不住 ABA 问题——
 *     T1 读到 PAYING，准备推进 SUCCESS
 *     T2 把它推进到 SUCCESS
 *     T3 触发关单（比如商户取消），状态变 CLOSED
 *     T1 的写入落地，状态被改回 SUCCESS   ← 已关闭的单被"诈尸"成支付成功
 *   所以必须有乐观锁：T1 提交时 version 已变，更新影响行数 0，由仓储抛并发异常，
 *   应用层重试或降级。
 *
 * 为什么不用分布式锁（Redis SETNX）：
 *   支付状态推进的冲突窗口极窄——同一笔单的"通道回调"和"主动查单"恰好同时到达的概率很低。
 *   为这种低概率冲突付出每次加锁的网络往返 + 锁超时/续约复杂度，不划算。
 *   乐观锁在冲突时的代价只是重试一次，平均开销为零。
 *
 *   例外场景（此时该用分布式锁）：批量关单、批量退款这种高冲突的批处理任务。
 *   单笔支付的状态推进，乐观锁是更优解。
 *
 * ==================== 为什么 PaymentAttempt 在聚合内 ====================
 *   "最多一个 active attempt"和"不超收"这两条不变量跨越多次尝试，
 *   必须由聚合根统一守护。详见 PaymentAttempt 的类注释。
 */
public class Payment {

    /** 单次支付单允许的最大尝试次数，超出即判定为最终失败（防止无限重试打爆通道）。 */
    public static final int MAX_ATTEMPTS = 3;

    private final String paymentId;
    private final String merchantId;
    private final String merchantOrderNo;
    private final String subject;
    private final Money amount;
    private final Instant createTime;
    private final Instant expireTime;

    private PaymentStatus status;
    private final List<PaymentAttempt> attempts = new ArrayList<>();

    /** 乐观锁版本号。每次状态变更 +1，仓储用 CAS 更新。 */
    private int version;

    private final List<DomainEvent> pendingEvents = new ArrayList<>();

    private Payment(String paymentId, String merchantId, String merchantOrderNo, String subject,
                    Money amount, Instant expireTime) {
        this.paymentId = paymentId;
        this.merchantId = merchantId;
        this.merchantOrderNo = merchantOrderNo;
        this.subject = subject;
        this.amount = amount;
        this.expireTime = expireTime;
        this.createTime = Instant.now();
        this.status = PaymentStatus.CREATED;
        this.version = 1;
    }

    /** 工厂：创建支付单。merchantOrderNo 是商户侧幂等键，唯一性由仓储唯一索引兜底。 */
    public static Payment create(String merchantId, String merchantOrderNo, String subject,
                                 Money amount, Instant expireTime) {
        if (merchantId == null || merchantId.isBlank()) {
            throw new IllegalArgumentException("商户号不能为空");
        }
        if (merchantOrderNo == null || merchantOrderNo.isBlank()) {
            throw new IllegalArgumentException("商户订单号不能为空");
        }
        if (amount == null || !amount.isPositive()) {
            throw new IllegalArgumentException("支付金额必须大于 0");
        }
        if (expireTime == null || !expireTime.isAfter(Instant.now())) {
            throw new IllegalArgumentException("过期时间必须晚于当前时间");
        }
        Payment p = new Payment(UUID.randomUUID().toString().replace("-", ""), merchantId,
                merchantOrderNo, subject, amount, expireTime);
        p.collectEvent(new PaymentCreatedEvent(p.paymentId, merchantId, merchantOrderNo,
                amount, expireTime));
        return p;
    }

    /** 重建：仓储还原。不产生领域事件，避免重放时事件二次发布。 */
    public static Payment restore(String paymentId, String merchantId, String merchantOrderNo,
                                  String subject, Money amount, Instant createTime,
                                  Instant expireTime, PaymentStatus status, int version,
                                  List<PaymentAttempt> attempts) {
        Payment p = new Payment(paymentId, merchantId, merchantOrderNo, subject, amount, expireTime);
        p.status = status;
        p.version = version;
        p.attempts.addAll(attempts);
        return p;
    }

    // ==================== 业务行为 ====================

    /**
     * 发起一次支付尝试。首次下单或换通道重试都走这里。
     *
     * @param channel  通道（重试时由应用层/路由策略决定换成哪个通道）
     * @param amount   本次尝试请求收取的金额。部分支付场景下可以是剩余待收，也可以是全额
     * @return 新建的尝试（只返回引用，外部不得修改其状态——状态只能由聚合根推进）
     */
    public PaymentAttempt startAttempt(ChannelCode channel, Money amount) {
        if (!status.canStartAttempt()) {
            throw new IllegalStateException(
                    String.format("当前状态[%s]不允许发起支付尝试", status));
        }
        if (attempts.size() >= MAX_ATTEMPTS) {
            throw new IllegalStateException(
                    String.format("已尝试 %d 次，达到上限，不允许再发起", MAX_ATTEMPTS));
        }
        PaymentAttempt active = activeAttempt();
        if (active != null) {
            throw new IllegalStateException(
                    String.format("已存在进行中的尝试（第 %d 次，通道 %s），不可并发下单",
                            active.attemptNo(), active.channel().code()));
        }
        if (!amount.currency().equals(this.amount.currency())) {
            throw new IllegalArgumentException("尝试金额的币种必须与应付金额一致");
        }
        if (amount.isGreaterThan(outstandingAmount())) {
            throw new IllegalArgumentException(
                    String.format("尝试金额[%s]超过待收金额[%s]", amount, outstandingAmount()));
        }

        PaymentAttempt attempt = new PaymentAttempt(attempts.size() + 1, channel, amount);
        attempts.add(attempt);
        this.status = PaymentStatus.PAYING;
        this.version++;
        return attempt;
    }

    /**
     * 确认某次尝试收款成功。可能推进到 SUCCESS（付清）或 PARTIAL（部分支付）。
     *
     * @return true 状态发生迁移；false 幂等（该尝试已是成功态）
     */
    public boolean confirmAttemptSuccess(int attemptNo, Money paidAmount, Instant paidAt) {
        PaymentAttempt attempt = requireAttempt(attemptNo);
        if (!attempt.succeed(paidAmount, paidAt)) {
            return false; // 该尝试已成功，幂等吞掉（通道重复回调的典型场景）
        }

        Money received = receivedAmount();
        if (received.isGreaterThan(amount)) {
            // 理论上走不到这里——startAttempt 已校验。但这是资损级不变量，必须双保险。
            throw new IllegalStateException(
                    String.format("累计已收[%s]超过应付[%s]，数据异常", received, amount));
        }

        if (received.equals(amount)) {
            this.status = PaymentStatus.SUCCESS;
            collectEvent(new PaymentSucceededEvent(paymentId, merchantId, merchantOrderNo,
                    received, lastSucceededTradeNo(), paidAt == null ? Instant.now() : paidAt));
        } else {
            this.status = PaymentStatus.PARTIAL;
        }
        this.version++;
        return true;
    }

    /**
     * 确认某次尝试失败。
     *
     * @param retriable 通道返回的失败是否可重试（余额不足可换卡/换通道重试；风控拒绝不可重试）
     * @return true 状态发生迁移；false 幂等
     */
    public boolean confirmAttemptFailure(int attemptNo, String failCode, String failReason,
                                         boolean retriable) {
        PaymentAttempt attempt = requireAttempt(attemptNo);
        if (!attempt.fail(failCode, failReason)) {
            return false;
        }
        // 已收到的钱不会因为新尝试失败而消失——保持 PARTIAL 而不是退回 FAILED
        boolean hasFunds = receivedAmount().isPositive();
        boolean canRetry = retriable && attempts.size() < MAX_ATTEMPTS;

        if (hasFunds) {
            this.status = PaymentStatus.PARTIAL;
        } else if (canRetry) {
            this.status = PaymentStatus.FAILED; // 可重试，不是终态
        } else {
            this.status = PaymentStatus.FAILED;
            collectEvent(new PaymentFailedEvent(paymentId, merchantOrderNo, failCode,
                    failReason, attempts.size()));
        }
        this.version++;
        return true;
    }

    /**
     * 关单。超时未付清或商户主动取消。
     *
     * 幂等：终态直接返回 false。这是超时任务与商户主动关单并发时的保护——
     * 定时任务扫到已关闭的单不会报错，商户重复点取消也不会产生两次事件。
     *
     * @return true 状态发生迁移；false 幂等
     */
    public boolean close(String reason) {
        if (status.isFinal()) {
            return false;
        }
        for (PaymentAttempt a : attempts) {
            a.abandon(reason);
        }
        this.status = PaymentStatus.CLOSED;
        this.version++;
        collectEvent(new PaymentClosedEvent(paymentId, merchantOrderNo, reason, receivedAmount()));
        return true;
    }

    // ==================== 查询 ====================

    /** 累计已收金额。 */
    public Money receivedAmount() {
        Money total = Money.zero(amount.currency());
        for (PaymentAttempt a : attempts) {
            if (a.status() == AttemptStatus.SUCCEEDED) {
                total = total.add(a.paidAmount());
            }
        }
        return total;
    }

    /** 剩余待收金额。部分支付后继续支付时用。 */
    public Money outstandingAmount() {
        return amount.subtract(receivedAmount());
    }

    /** 是否已过期。超时关单任务据此判断。 */
    public boolean isExpired(Instant now) {
        return !status.isFinal() && now.isAfter(expireTime);
    }

    /** 当前进行中的尝试，没有则 null。 */
    public PaymentAttempt activeAttempt() {
        for (PaymentAttempt a : attempts) {
            if (a.isActive()) {
                return a;
            }
        }
        return null;
    }

    private PaymentAttempt requireAttempt(int attemptNo) {
        for (PaymentAttempt a : attempts) {
            if (a.attemptNo() == attemptNo) {
                return a;
            }
        }
        throw new IllegalArgumentException("支付单不存在第 " + attemptNo + " 次尝试");
    }

    private String lastSucceededTradeNo() {
        String tradeNo = null;
        for (PaymentAttempt a : attempts) {
            if (a.status() == AttemptStatus.SUCCEEDED) {
                tradeNo = a.channelTradeNo();
            }
        }
        return tradeNo;
    }

    // ==================== 事件与版本 ====================

    private void collectEvent(DomainEvent event) {
        pendingEvents.add(event);
    }

    /** 取走待发布事件（由应用层在事务提交后统一发布）。 */
    public List<DomainEvent> drainEvents() {
        List<DomainEvent> events = new ArrayList<>(pendingEvents);
        pendingEvents.clear();
        return events;
    }

    /** 乐观锁版本号。仓储用 CAS 更新：UPDATE ... WHERE id=? AND version=? */
    public int version() {
        return version;
    }

    // ==================== getter ====================

    public String paymentId() { return paymentId; }
    public String merchantId() { return merchantId; }
    public String merchantOrderNo() { return merchantOrderNo; }
    public String subject() { return subject; }
    public Money amount() { return amount; }
    public Instant createTime() { return createTime; }
    public Instant expireTime() { return expireTime; }
    public PaymentStatus status() { return status; }
    public List<PaymentAttempt> attempts() { return Collections.unmodifiableList(attempts); }
}
