package com.zx.payment.acquisition.domain.model;

import com.zx.payment.shared.ChannelCode;
import com.zx.payment.shared.Money;

import java.time.Instant;
import java.util.Objects;

/**
 * 实体：一次支付尝试。位于 Payment 聚合【内部】，局部标识是 attemptNo。
 *
 * 为什么它在聚合内，而"通道调用流水"不在——这是 v1 踩过的坑，值得说清楚：
 *
 *  聚合内该放的是【必须强一致守护的不变量】。这里的不变量是：
 *    "同一时刻最多只有一个 active attempt，且累计已收金额不得超过应付金额"。
 *  这条规则跨越多个 attempt，一旦被破坏就会出现重复扣款或超收，是资损级事故。
 *  所以它必须由聚合根统一守护 → attempt 必须在聚合内。
 *
 *  而"通道调用流水"（每次 HTTP 请求/响应报文）是技术日志：
 *    - 没有任何业务不变量依赖它；
 *    - 会随网络重试无限增长，放聚合内会让每次加载支付单都拖出全部历史报文；
 *    - 它属于可观测性范畴，应走独立的读模型/日志系统。
 *  v1 把它放进了聚合，既拖慢性能又模糊了模型语义。
 *
 * 判据（Vaughn Vernon 的聚合设计第二条的实用化表述）：
 *  一个对象是否该进聚合，看删掉它会不会破坏某条业务不变量。
 *  删掉 attempt → 无法保证不超收 → 必须进。
 *  删掉调用流水 → 业务照常运转，只是不好排查 → 不该进。
 */
public final class PaymentAttempt {

    private final int attemptNo;
    private final ChannelCode channel;
    private final Money requestedAmount;
    private final Instant startedAt;

    private AttemptStatus status;
    private String channelTradeNo;
    private Money paidAmount;
    private String failCode;
    private String failReason;
    private Instant finishedAt;

    PaymentAttempt(int attemptNo, ChannelCode channel, Money requestedAmount) {
        if (attemptNo < 1) {
            throw new IllegalArgumentException("尝试序号从 1 开始");
        }
        this.attemptNo = attemptNo;
        this.channel = Objects.requireNonNull(channel, "通道不能为空");
        this.requestedAmount = Objects.requireNonNull(requestedAmount, "请求金额不能为空");
        if (!requestedAmount.isPositive()) {
            throw new IllegalArgumentException("请求金额必须大于 0");
        }
        this.startedAt = Instant.now();
        this.status = AttemptStatus.INITIATED;
        this.paidAmount = Money.zero(requestedAmount.currency());
    }

    /** 仓储还原用。 */
    static PaymentAttempt restore(int attemptNo, ChannelCode channel, Money requestedAmount,
                                  AttemptStatus status, String channelTradeNo, Money paidAmount,
                                  String failCode, String failReason,
                                  Instant startedAt, Instant finishedAt) {
        PaymentAttempt a = new PaymentAttempt(attemptNo, channel, requestedAmount);
        a.status = status;
        a.channelTradeNo = channelTradeNo;
        a.paidAmount = paidAmount;
        a.failCode = failCode;
        a.failReason = failReason;
        a.finishedAt = finishedAt;
        return a;
    }

    // ==================== 行为（只能由聚合根调用）====================

    void markPaying(String channelTradeNo) {
        if (status != AttemptStatus.INITIATED) {
            throw new IllegalStateException(
                    String.format("当前尝试状态[%s]不可推进到 PAYING", status));
        }
        if (channelTradeNo == null || channelTradeNo.isBlank()) {
            throw new IllegalArgumentException("通道交易单号不能为空");
        }
        this.status = AttemptStatus.PAYING;
        this.channelTradeNo = channelTradeNo;
    }

    /**
     * 本次尝试收款成功。
     * @param paid 实收金额，必须为正且不超过本次请求金额（部分支付时 paid < requested）
     * @return true 表示本次调用真的推进了状态；false 表示已终态，幂等吞掉
     */
    boolean succeed(Money paid, Instant paidAt) {
        if (status == AttemptStatus.SUCCEEDED) {
            return false;
        }
        if (status != AttemptStatus.PAYING && status != AttemptStatus.INITIATED) {
            throw new IllegalStateException(
                    String.format("当前尝试状态[%s]不可推进到 SUCCEEDED", status));
        }
        Objects.requireNonNull(paid, "实收金额不能为空");
        if (!paid.isPositive()) {
            throw new IllegalArgumentException("实收金额必须大于 0");
        }
        if (paid.isGreaterThan(requestedAmount)) {
            // 通道多收了——这是必须拦住的资损风险，宁可报错让人工介入，也不能默默记账
            throw new IllegalArgumentException(
                    String.format("实收金额[%s]超过本次请求金额[%s]，疑似通道异常", paid, requestedAmount));
        }
        this.status = AttemptStatus.SUCCEEDED;
        this.paidAmount = paid;
        this.finishedAt = paidAt == null ? Instant.now() : paidAt;
        return true;
    }

    boolean fail(String code, String reason) {
        if (status == AttemptStatus.FAILED) {
            return false;
        }
        if (status.isTerminal()) {
            throw new IllegalStateException(
                    String.format("当前尝试状态[%s]不可推进到 FAILED", status));
        }
        this.status = AttemptStatus.FAILED;
        this.failCode = code;
        this.failReason = reason;
        this.finishedAt = Instant.now();
        return true;
    }

    /** 支付单关闭时作废未完成的尝试。 */
    void abandon(String reason) {
        if (status.isTerminal()) {
            return;
        }
        this.status = AttemptStatus.ABANDONED;
        this.failReason = reason;
        this.finishedAt = Instant.now();
    }

    // ==================== 查询 ====================

    public boolean isActive() {
        return status.isActive();
    }

    public int attemptNo() { return attemptNo; }
    public ChannelCode channel() { return channel; }
    public Money requestedAmount() { return requestedAmount; }
    public AttemptStatus status() { return status; }
    public String channelTradeNo() { return channelTradeNo; }
    public Money paidAmount() { return paidAmount; }
    public String failCode() { return failCode; }
    public String failReason() { return failReason; }
    public Instant startedAt() { return startedAt; }
    public Instant finishedAt() { return finishedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PaymentAttempt other)) return false;
        return attemptNo == other.attemptNo;
    }

    @Override
    public int hashCode() {
        return Objects.hash(attemptNo);
    }
}
