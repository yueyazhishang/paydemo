package com.payment.domain.payment.event;

import com.payment.domain.channel.model.enums.ChannelCode;
import com.payment.domain.payment.model.valueobject.Money;
import com.payment.domain.payment.model.valueobject.PaymentId;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 支付成功事件
 */
@Getter
public class PaymentSuccessEvent {
    
    private final PaymentId paymentId;
    private final String merchantId;
    private final String channelOrderId;
    private final ChannelCode channelCode;
    private final Money amount;
    private final LocalDateTime occurredAt;
    
    public PaymentSuccessEvent(PaymentId paymentId, String merchantId, 
                                String channelOrderId, ChannelCode channelCode, Money amount) {
        this.paymentId = paymentId;
        this.merchantId = merchantId;
        this.channelOrderId = channelOrderId;
        this.channelCode = channelCode;
        this.amount = amount;
        this.occurredAt = LocalDateTime.now();
    }
}
