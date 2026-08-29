package com.payment.domain.payment.event;

import com.payment.domain.payment.model.valueobject.Money;
import com.payment.domain.payment.model.valueobject.OrderId;
import com.payment.domain.payment.model.valueobject.PaymentId;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 支付订单创建事件
 */
@Getter
public class PaymentCreatedEvent {
    
    private final PaymentId paymentId;
    private final OrderId merchantOrderId;
    private final String merchantId;
    private final Money amount;
    private final LocalDateTime occurredAt;
    
    public PaymentCreatedEvent(PaymentId paymentId, OrderId merchantOrderId, 
                                String merchantId, Money amount) {
        this.paymentId = paymentId;
        this.merchantOrderId = merchantOrderId;
        this.merchantId = merchantId;
        this.amount = amount;
        this.occurredAt = LocalDateTime.now();
    }
}
