package com.demo.payment.domain.acquiring.event;

import com.demo.payment.domain.channel.model.ChannelCode;
import com.demo.payment.domain.channel.model.PaymentMethodType;
import com.demo.payment.shared.event.DomainEvent;
import com.demo.payment.shared.money.Money;

import java.time.Instant;

/**
 * 支付成功 —— 领域事件。
 *
 * <p>事件是"已发生的事实"，因此字段不可变（record）。
 * 消费方（结算、账务、风控、商户通知）订阅此事件做后续处理，
 * 支付主链路不感知它们的存在。
 */
public record PaymentSucceeded(
        String aggregateId,
        String merchantOrderNo, String outTradeNo, ChannelCode channelCode, String channelTransactionId, Money amount,
        Instant occurredAt
) implements DomainEvent {

    public PaymentSucceeded(String aggregateId, String merchantOrderNo, String outTradeNo, ChannelCode channelCode, String channelTransactionId, Money amount, Instant occurredAt) {
        this.aggregateId = aggregateId;
        this.merchantOrderNo = merchantOrderNo;
        this.outTradeNo = outTradeNo;
        this.channelCode = channelCode;
        this.channelTransactionId = channelTransactionId;
        this.amount = amount;
        this.occurredAt = occurredAt;
    }

    public String merchantOrderNo() { return merchantOrderNo; }
    public String outTradeNo() { return outTradeNo; }
    public ChannelCode channelCode() { return channelCode; }
    public String channelTransactionId() { return channelTransactionId; }
    public Money amount() { return amount; }
}
