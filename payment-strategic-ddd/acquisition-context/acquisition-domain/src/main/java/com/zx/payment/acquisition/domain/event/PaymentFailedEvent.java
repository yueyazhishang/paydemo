package com.zx.payment.acquisition.domain.event;

import com.zx.payment.shared.DomainEvent;

/**
 * 领域事件：支付失败（所有可用通道尝试均失败，或通道明确不可重试的失败）。
 *
 * 注意：单次 attempt 失败不发这个事件——那次失败可能触发换通道重试，整单还没走到绝路。
 * 只有确认不再重试时才发。
 */
public final class PaymentFailedEvent extends DomainEvent {

    private final String merchantOrderNo;
    private final String failCode;
    private final String failReason;
    private final int attemptCount;

    public PaymentFailedEvent(String paymentId, String merchantOrderNo, String failCode,
                              String failReason, int attemptCount) {
        super(paymentId);
        this.merchantOrderNo = merchantOrderNo;
        this.failCode = failCode;
        this.failReason = failReason;
        this.attemptCount = attemptCount;
    }

    public String merchantOrderNo() { return merchantOrderNo; }
    public String failCode() { return failCode; }
    public String failReason() { return failReason; }
    public int attemptCount() { return attemptCount; }
}
