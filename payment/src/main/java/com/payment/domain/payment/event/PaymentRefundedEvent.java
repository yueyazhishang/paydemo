package com.payment.domain.payment.event;

import com.payment.domain.payment.model.valueobject.Money;
import com.payment.domain.payment.model.valueobject.PaymentId;
import com.payment.domain.payment.model.valueobject.RefundId;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 退款事件
 */
@Getter
public class PaymentRefundedEvent {
    
    private final PaymentId paymentId;
    private final RefundId refundId;
    private final Money refundAmount;
    private final boolean isFullRefund;
    private final LocalDateTime occurredAt;
    
    public PaymentRefundedEvent(PaymentId paymentId, RefundId refundId, 
                                 Money refundAmount, boolean isFullRefund) {
        this.paymentId = paymentId;
        this.refundId = refundId;
        this.refundAmount = refundAmount;
        this.isFullRefund = isFullRefund;
        this.occurredAt = LocalDateTime.now();
    }
}
