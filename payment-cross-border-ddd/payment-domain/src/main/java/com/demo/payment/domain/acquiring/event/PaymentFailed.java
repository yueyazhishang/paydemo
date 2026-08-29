package com.demo.payment.domain.acquiring.event;

import com.demo.payment.domain.channel.model.ChannelCode;
import com.demo.payment.domain.channel.model.PaymentMethodType;
import com.demo.payment.shared.event.DomainEvent;
import com.demo.payment.shared.money.Money;

import java.time.Instant;

/**
 * 支付失败（所有尝试均已失败） —— 领域事件。
 *
 * <p>事件是"已发生的事实"，因此字段不可变（record）。
 * 消费方（结算、账务、风控、商户通知）订阅此事件做后续处理，
 * 支付主链路不感知它们的存在。
 */
public record PaymentFailed(
        String aggregateId,
        String merchantOrderNo, String reason,
        Instant occurredAt
) implements DomainEvent {

    public PaymentFailed(String aggregateId, String merchantOrderNo, String reason, Instant occurredAt) {
        this.aggregateId = aggregateId;
        this.merchantOrderNo = merchantOrderNo;
        this.reason = reason;
        this.occurredAt = occurredAt;
    }

    public String merchantOrderNo() { return merchantOrderNo; }
    public String reason() { return reason; }
}
