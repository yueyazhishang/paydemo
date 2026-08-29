package com.demo.payment.domain.acquiring.event;

import com.demo.payment.domain.channel.model.ChannelCode;
import com.demo.payment.domain.channel.model.PaymentMethodType;
import com.demo.payment.shared.event.DomainEvent;
import com.demo.payment.shared.money.Money;

import java.time.Instant;

/**
 * 支付单已创建 —— 领域事件。
 *
 * <p>事件是"已发生的事实"，因此字段不可变（record）。
 * 消费方（结算、账务、风控、商户通知）订阅此事件做后续处理，
 * 支付主链路不感知它们的存在。
 */
public record PaymentOrderCreated(
        String aggregateId,
        String merchantId, String merchantOrderNo, Money amount, PaymentMethodType paymentMethod,
        Instant occurredAt
) implements DomainEvent {

    public PaymentOrderCreated(String aggregateId, String merchantId, String merchantOrderNo, Money amount, PaymentMethodType paymentMethod, Instant occurredAt) {
        this.aggregateId = aggregateId;
        this.merchantId = merchantId;
        this.merchantOrderNo = merchantOrderNo;
        this.amount = amount;
        this.paymentMethod = paymentMethod;
        this.occurredAt = occurredAt;
    }

    public String merchantId() { return merchantId; }
    public String merchantOrderNo() { return merchantOrderNo; }
    public Money amount() { return amount; }
    public PaymentMethodType paymentMethod() { return paymentMethod; }
}
