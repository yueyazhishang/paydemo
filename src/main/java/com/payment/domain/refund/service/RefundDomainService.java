package com.payment.domain.refund.service;

import com.payment.domain.payment.model.aggregate.PaymentOrder;
import com.payment.domain.payment.model.valueobject.Money;
import com.payment.domain.payment.model.valueobject.RefundId;
import com.payment.domain.payment.repository.PaymentOrderRepository;
import com.payment.domain.refund.model.aggregate.RefundOrder;
import com.payment.domain.refund.repository.RefundOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 退款领域服务
 * 
 * 处理退款相关的跨聚合业务逻辑
 */
@Service
@RequiredArgsConstructor
public class RefundDomainService {
    
    private final RefundOrderRepository refundOrderRepository;
    private final PaymentOrderRepository paymentOrderRepository;
    
    /**
     * 创建退款订单
     * 
     * 领域服务负责:
     * 1. 验证原支付订单状态
     * 2. 验证退款金额
     * 3. 创建退款订单
     * 4. 更新支付订单的退款金额
     */
    @Transactional
    public RefundOrder createRefundOrder(PaymentOrder paymentOrder, Money refundAmount, 
                                          String reason, String notifyUrl) {
        // 验证支付订单状态
        if (!paymentOrder.getStatus().canRefund()) {
            throw new IllegalArgumentException("支付订单状态不允许退款: " + paymentOrder.getStatus());
        }
        
        // 验证退款金额
        Money refundableAmount = paymentOrder.getRefundableAmount();
        if (refundAmount.isGreaterThan(refundableAmount)) {
            throw new IllegalArgumentException(
                String.format("退款金额[%s]超过可退款金额[%s]", refundAmount, refundableAmount));
        }
        
        // 创建退款订单
        RefundOrder refundOrder = RefundOrder.builder()
                .refundId(RefundId.generate())
                .paymentId(paymentOrder.getPaymentId())
                .merchantId(paymentOrder.getMerchantId())
                .refundAmount(refundAmount)
                .reason(reason)
                .channelCode(paymentOrder.getChannelCode())
                .notifyUrl(notifyUrl)
                .build();
        
        // 保存退款订单
        refundOrderRepository.save(refundOrder);
        
        // 更新支付订单退款金额
        paymentOrder.addRefundedAmount(refundAmount);
        paymentOrderRepository.save(paymentOrder);
        
        return refundOrder;
    }
    
    /**
     * 判断是否可以退款
     */
    public boolean canRefund(PaymentOrder paymentOrder) {
        return paymentOrder.getStatus().canRefund() && 
               paymentOrder.getRefundableAmount().isPositive();
    }
}
