package com.zx.payment.refund.domain.model;

import com.zx.payment.shared.DomainEvent;
import com.zx.payment.shared.Money;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 聚合根：退款单（退款上下文）。
 *
 * 与收单上下文的关系：只通过 paymentId 这个【字符串】引用，不持有 Payment 对象。
 * 需要判断可退性时，拿的是 PaidFact（支付事实快照），不是活的聚合。
 *
 * 关于"可退余额"的一致性——这是 v1 的另一个错误，值得展开：
 *
 *   v1 做法：RefundAppService 在【同一个事务】里同时改 Refund 和 Payment，
 *            用强事务保证"累计退款不超额"。
 *   问题：这是骑墙。物理上分了聚合（想拿独立聚合的并发优势），
 *         逻辑上又用事务绑死（又享受不到），两头不靠：
 *         - 高并发退款时 Payment 成为锁热点；
 *         - 两个上下文必须共享数据库，无法独立部署。
 *
 *   v2 做法：退款上下文【自己】持有一份已退金额，基于 PaidFact 判断可退性。
 *           Payment 侧的可退余额由收单上下文消费 RefundSucceeded 事件自行更新。
 *           两边最终一致，用对账兜底。
 *
 * 为什么敢用最终一致：
 *   退款是低频操作（相比支付），且通道侧本身就有 T+1 延迟，
 *   强一致带来的收益远小于它锁死两个上下文的代价。
 *   真正的防超退防线在通道侧——通道会拒绝超过原金额的退款。
 */
public class Refund {

    private final String refundId;
    private final String refundNo;
    private final String paymentId;
    private final PaidFact paidFact;
    private final String reason;
    private final Instant createTime;

    private final Money amount;
    private RefundStatus status;
    private String channelRefundNo;
    private String failCode;
    private String failReason;
    private Instant finishTime;

    private final List<DomainEvent> pendingEvents = new ArrayList<>();

    private Refund(String refundId, String refundNo, String paymentId, PaidFact paidFact,
                   Money amount, String reason) {
        this.refundId = refundId;
        this.refundNo = refundNo;
        this.paymentId = paymentId;
        this.paidFact = paidFact;
        this.amount = amount;
        this.reason = reason;
        this.createTime = Instant.now();
        this.status = RefundStatus.PROCESSING;
    }

    /**
     * 工厂：基于支付事实发起退款。
     *
     * @param paidFact   支付事实（由收单上下文经发布语言投递、本上下文防腐层翻译而来）
     * @param amount     本次退款金额
     * @param refundedSoFar 该支付单【已退金额】——由本上下文自行维护，不查收单上下文
     */
    public static Refund apply(String refundNo, PaidFact paidFact, Money amount,
                               Money refundedSoFar, String reason) {
        if (refundNo == null || refundNo.isBlank()) {
            throw new IllegalArgumentException("退款单号不能为空");
        }
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("退款金额必须大于 0");
        }
        if (!amount.currency().equals(paidFact.paidAmount().currency())) {
            throw new IllegalArgumentException("退款币种必须与支付币种一致");
        }

        Money refundable = paidFact.paidAmount().subtract(refundedSoFar);
        if (amount.isGreaterThan(refundable)) {
            throw new IllegalArgumentException(
                    String.format("退款金额[%s]超过可退余额[%s]（已付 %s，已退 %s）",
                            amount, refundable, paidFact.paidAmount(), refundedSoFar));
        }

        return new Refund(UUID.randomUUID().toString().replace("-", ""), refundNo,
                paidFact.paymentId(), paidFact, amount, reason);
    }

    /** 重建：仓储还原。 */
    public static Refund restore(String refundId, String refundNo, String paymentId,
                                 PaidFact paidFact, Money amount, String reason,
                                 RefundStatus status, String channelRefundNo, String failCode,
                                 String failReason, Instant createTime, Instant finishTime) {
        Refund r = new Refund(refundId, refundNo, paymentId, paidFact, amount, reason);
        r.status = status;
        r.channelRefundNo = channelRefundNo;
        r.failCode = failCode;
        r.failReason = failReason;
        r.finishTime = finishTime;
        return r;
    }

    // ==================== 业务行为 ====================

    /** 通道已受理退款申请，记录通道退款单号。 */
    public void markAccepted(String channelRefundNo) {
        if (status != RefundStatus.PROCESSING) {
            throw new IllegalStateException(String.format("当前状态[%s]不可受理", status));
        }
        this.channelRefundNo = channelRefundNo;
    }

    /**
     * 退款成功。幂等：已终态返回 false。
     * 注意：通道受理 ≠ 退款成功。跨境退款可能 T+1 才到账，终态必须由回调/查单推进。
     */
    public boolean succeed(Instant finishedAt) {
        if (status.isTerminal()) {
            return false;
        }
        this.status = RefundStatus.SUCCEEDED;
        this.finishTime = finishedAt == null ? Instant.now() : finishedAt;
        return true;
    }

    public boolean fail(String code, String reason) {
        if (status.isTerminal()) {
            return false;
        }
        this.status = RefundStatus.FAILED;
        this.failCode = code;
        this.failReason = reason;
        this.finishTime = Instant.now();
        return true;
    }

    public List<DomainEvent> drainEvents() {
        List<DomainEvent> events = new ArrayList<>(pendingEvents);
        pendingEvents.clear();
        return events;
    }

    // ==================== getter ====================

    public String refundId() { return refundId; }
    public String refundNo() { return refundNo; }
    public String paymentId() { return paymentId; }
    public PaidFact paidFact() { return paidFact; }
    public Money amount() { return amount; }
    public String reason() { return reason; }
    public RefundStatus status() { return status; }
    public String channelRefundNo() { return channelRefundNo; }
    public String failCode() { return failCode; }
    public String failReason() { return failReason; }
    public Instant createTime() { return createTime; }
    public Instant finishTime() { return finishTime; }
}
