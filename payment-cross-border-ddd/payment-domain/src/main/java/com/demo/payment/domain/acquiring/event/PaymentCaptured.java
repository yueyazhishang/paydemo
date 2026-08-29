package com.demo.payment.domain.acquiring.event;

import com.demo.payment.domain.channel.model.ChannelCode;
import com.demo.payment.domain.channel.model.PaymentMethodType;
import com.demo.payment.shared.event.DomainEvent;
import com.demo.payment.shared.money.Money;

import java.time.Instant;

/**
 * 请款成功（两段式第二步完成） —— 领域事件。
 *
 * <p>事件是"已发生的事实"，因此字段不可变（record）。
 * 消费方（结算、账务、风控、商户通知）订阅此事件做后续处理，
 * 支付主链路不感知它们的存在。
 */
public record PaymentCaptured(
        String aggregateId,
        String merchantOrderNo, String channelTransactionId,
        Instant occurredAt
) implements DomainEvent {

    public PaymentCaptured(String aggregateId, String merchantOrderNo, String channelTransactionId, Instant occurredAt) {
        this.aggregateId = aggregateId;
        this.merchantOrderNo = merchantOrderNo;
        this.channelTransactionId = channelTransactionId;
        this.occurredAt = occurredAt;
    }

    public String merchantOrderNo() { return merchantOrderNo; }
    public String channelTransactionId() { return channelTransactionId; }
}
