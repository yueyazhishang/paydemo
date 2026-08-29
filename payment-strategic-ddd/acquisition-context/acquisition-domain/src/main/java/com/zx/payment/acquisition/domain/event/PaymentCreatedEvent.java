package com.zx.payment.acquisition.domain.event;

import com.zx.payment.shared.DomainEvent;
import com.zx.payment.shared.Money;

import java.time.Instant;

/**
 * 领域事件：支付单已创建（上下文内部事件，非跨上下文契约）。
 *
 * 订阅方：超时关单定时器（据此注册延迟任务）、风控、埋点。
 */
public final class PaymentCreatedEvent extends DomainEvent {

    private final String merchantId;
    private final String merchantOrderNo;
    private final Money amount;
    private final Instant expireTime;

    public PaymentCreatedEvent(String paymentId, String merchantId, String merchantOrderNo,
                               Money amount, Instant expireTime) {
        super(paymentId);
        this.merchantId = merchantId;
        this.merchantOrderNo = merchantOrderNo;
        this.amount = amount;
        this.expireTime = expireTime;
    }

    public String merchantId() { return merchantId; }
    public String merchantOrderNo() { return merchantOrderNo; }
    public Money amount() { return amount; }
    public Instant expireTime() { return expireTime; }
}
