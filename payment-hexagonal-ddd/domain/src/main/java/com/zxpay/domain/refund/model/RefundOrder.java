package com.zxpay.domain.refund.model;

import com.zxpay.domain.channel.model.ChannelCode;
import com.zxpay.domain.merchant.model.MerchantAppId;
import com.zxpay.domain.payment.model.FailureInfo;
import com.zxpay.domain.payment.model.PaymentOrderId;
import com.zxpay.domain.refund.event.RefundEvents;
import com.zxpay.sharedkernel.exception.DomainException;
import com.zxpay.sharedkernel.model.AggregateRoot;
import com.zxpay.sharedkernel.money.Money;

import java.time.Instant;
import java.util.Optional;

/**
 * 退款单聚合根。
 *
 * <p><b>为什么它是独立聚合而不是支付单的一部分？</b>（聚合设计的经典案例）
 *
 * <p>判断标准是<b>不变量</b>，不是「看起来像父子」：
 * <ul>
 *   <li>支付单需要守的不变量是「同一时刻只有一个进行中的通道尝试」——
 *       必须在一个事务里改，所以尝试是内部实体。</li>
 *   <li>退款需要守的不变量是「累计退款不超过实付」——
 *       只靠支付单上一个数值字段 + 乐观锁就能保证，
 *       不需要把退款单装箱进支付单。</li>
 * </ul>
 *
 * <p>若强行内嵌，后果是：支付单随退款次数线性膨胀；
 * 每次退款都要加载整个支付单；并发的部分退款在聚合锁上串行化。
 * 而收益为零——因为不存在需要跨退款单强一致的业务规则。
 *
 * <p><b>跨聚合的一致性怎么处理？</b>
 * 用「预留 - 确认」两段式：创建退款单时调
 * {@code PaymentOrder.reserveRefund()} 占用金额，
 * 退款成功调 {@code applyRefundSucceeded()} 落定，
 * 失败调 {@code applyRefundFailed()} 释放。
 * 这两步由应用层在一个事务里完成（同一数据库），
 * 因此不需要分布式事务，也没有最终一致的窗口期。
 */
public final class RefundOrder extends AggregateRoot<RefundOrderId> {

    private final RefundOrderId refundId;
    private final PaymentOrderId paymentOrderId;
    private final MerchantAppId appId;
    private final String merchantRefundNo;

    /** 退款金额，创建后不可变。 */
    private final Money amount;

    /** 原支付金额快照。避免每次校验都要回查支付单。 */
    private final Money originalAmount;

    private final ChannelCode channel;

    /** 原支付在通道侧的交易号。退款必须指定它。 */
    private final String channelTransactionId;

    /** 通道退款幂等键。确定性生成，重试复用。 */
    private final String refundIdempotencyKey;

    private final String reason;
    private final Instant createdAt;

    private RefundStatus status;
    private String channelRefundId;
    private String failureCode;
    private String failureMessage;
    private Money refundedAmount;
    private Instant submittedAt;
    private Instant succeededAt;
    private Instant updatedAt;

    private RefundOrder(RefundOrderId refundId, PaymentOrderId paymentOrderId, MerchantAppId appId,
                        String merchantRefundNo, Money amount, Money originalAmount, ChannelCode channel,
                        String channelTransactionId, String reason, Instant createdAt) {
        this.refundId = refundId;
        this.paymentOrderId = paymentOrderId;
        this.appId = appId;
        this.merchantRefundNo = merchantRefundNo;
        this.amount = amount;
        this.originalAmount = originalAmount;
        this.channel = channel;
        this.channelTransactionId = channelTransactionId;
        this.refundIdempotencyKey = "rfd:" + appId.value() + ":" + merchantRefundNo;
        this.reason = reason;
        this.createdAt = createdAt;
        this.status = RefundStatus.CREATED;
        this.refundedAmount = Money.zero(amount.currency());
        this.updatedAt = createdAt;
    }

    public static RefundOrder create(RefundOrderId refundId,
                                     PaymentOrderId paymentOrderId,
                                     MerchantAppId appId,
                                     String merchantRefundNo,
                                     Money amount,
                                     Money originalAmount,
                                     ChannelCode channel,
                                     String channelTransactionId,
                                     String reason,
                                     Instant now) {
        if (merchantRefundNo == null || merchantRefundNo.isBlank()) {
            throw new DomainException("MERCHANT_REFUND_NO_REQUIRED", "merchantRefundNo must not be blank");
        }
        if (amount == null || !amount.isPositive()) {
            throw new DomainException("REFUND_AMOUNT_INVALID", "refund amount must be positive: " + amount);
        }
        if (amount.currency() != originalAmount.currency()) {
            throw new DomainException("REFUND_CURRENCY_MISMATCH",
                    "refund currency " + amount.currency() + " differs from original " + originalAmount.currency());
        }
        if (channelTransactionId == null || channelTransactionId.isBlank()) {
            throw new DomainException("CHANNEL_TRANSACTION_ID_REQUIRED",
                    "channelTransactionId is required for refund");
        }

        RefundOrder order = new RefundOrder(refundId, paymentOrderId, appId, merchantRefundNo, amount,
                originalAmount, channel, channelTransactionId, reason, now);
        order.registerEvent(new RefundEvents.RefundOrderCreated(
                refundId, paymentOrderId, appId, amount, channel, reason));
        return order;
    }

    // ---------- 生命周期 ----------

    public void markSubmitted(String channelRefundId, Instant now) {
        if (status != RefundStatus.CREATED) {
            throw new DomainException("REFUND_STATUS_INVALID",
                    "refund " + refundId.value() + " cannot be submitted from status " + status);
        }
        this.status = RefundStatus.SUBMITTED;
        this.channelRefundId = channelRefundId;
        this.submittedAt = now;
        this.updatedAt = now;
    }

    /**
     * 应用通道退款结果。
     *
     * <p>幂等：重复的成功通知不会重复累加金额，也不会重复发事件。
     */
    public void applyResult(ChannelRefundResult result, Instant now) {
        if (status.isTerminal()) {
            return;   // 终态忽略，重复通知直接吞掉
        }

        if (result.channelRefundId() != null) {
            this.channelRefundId = result.channelRefundId();
        }
        this.updatedAt = now;

        switch (result.normalizedStatus()) {
            case SUCCEEDED -> {
                this.status = RefundStatus.SUCCEEDED;
                this.refundedAmount = result.refundedAmount() != null ? result.refundedAmount() : amount;
                this.succeededAt = result.refundedAt() != null ? result.refundedAt() : now;
                this.failureCode = null;
                this.failureMessage = null;
                registerEvent(new RefundEvents.RefundSucceeded(
                        refundId, paymentOrderId, refundedAmount, channel, channelRefundId, succeededAt));
            }
            case PROCESSING -> this.status = RefundStatus.PROCESSING;
            case FAILED -> {
                FailureInfo failure = result.failure();
                this.status = RefundStatus.FAILED;
                this.failureCode = failure == null ? null : failure.code();
                this.failureMessage = failure == null ? null : failure.message();
                boolean retryable = failure != null && failure.retryable();
                registerEvent(new RefundEvents.RefundFailed(
                        refundId, paymentOrderId, amount, channel, failureCode, failureMessage, retryable));
            }
            default -> { /* CREATED / CANCELLED 不处理 */ }
        }
    }

    /** 提交前取消。已提交通道的退款不能取消，只能等结果或再发起一笔反向操作。 */
    public void cancel(Instant now) {
        if (status.submitted()) {
            throw new DomainException("REFUND_ALREADY_SUBMITTED",
                    "submitted refund cannot be cancelled: " + refundId.value());
        }
        this.status = RefundStatus.CANCELLED;
        this.updatedAt = now;
    }

    // ---------- 读取 ----------

    @Override public RefundOrderId id() { return refundId; }

    public PaymentOrderId paymentOrderId() { return paymentOrderId; }
    public MerchantAppId appId() { return appId; }
    public String merchantRefundNo() { return merchantRefundNo; }
    public Money amount() { return amount; }
    public Money originalAmount() { return originalAmount; }
    public Money refundedAmount() { return refundedAmount; }
    public ChannelCode channel() { return channel; }
    public String channelTransactionId() { return channelTransactionId; }
    public String refundIdempotencyKey() { return refundIdempotencyKey; }
    public String reason() { return reason; }
    public RefundStatus status() { return status; }
    public Instant createdAt() { return createdAt; }
    public Instant submittedAt() { return submittedAt; }
    public Instant succeededAt() { return succeededAt; }
    public Instant updatedAt() { return updatedAt; }

    public Optional<String> channelRefundId() { return Optional.ofNullable(channelRefundId); }
    public Optional<String> failureCode() { return Optional.ofNullable(failureCode); }
    public Optional<String> failureMessage() { return Optional.ofNullable(failureMessage); }

    /** 是否为部分退款。 */
    public boolean isPartial() {
        return amount.isLessThan(originalAmount);
    }
}
