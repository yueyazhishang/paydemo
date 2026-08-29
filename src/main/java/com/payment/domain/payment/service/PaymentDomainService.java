package com.payment.domain.payment.service;

import com.payment.domain.channel.model.enums.ChannelCode;
import com.payment.domain.payment.model.aggregate.PaymentOrder;
import com.payment.domain.payment.model.valueobject.Money;
import com.payment.domain.payment.model.valueobject.OrderId;
import com.payment.domain.payment.model.valueobject.PaymentId;
import com.payment.domain.payment.repository.PaymentOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 支付领域服务
 * 
 * 处理跨聚合的业务逻辑，协调多个领域对象
 * 领域服务特点:
 * 1. 无状态
 * 2. 处理不属于任何单一聚合根的领域逻辑
 * 3. 协调多个聚合根的操作
 */
@Service
@RequiredArgsConstructor
public class PaymentDomainService {
    
    private final PaymentOrderRepository paymentOrderRepository;
    
    /**
     * 创建支付订单
     * 
     * 领域服务职责:
     * 1. 生成唯一ID
     * 2. 检查业务规则
     * 3. 创建聚合根
     * 4. 持久化
     */
    @Transactional
    public PaymentOrder createPaymentOrder(String merchantId, String userId,
                                           OrderId merchantOrderId, Money amount,
                                           String description, ChannelCode channelCode,
                                           String notifyUrl, String returnUrl,
                                           java.util.Map<String, String> extraParams) {
        // 检查商户订单是否已存在
        paymentOrderRepository.findByMerchantOrderId(merchantId, merchantOrderId)
                .ifPresent(existing -> {
                    throw new IllegalArgumentException("商户订单已存在: " + merchantOrderId.getValue());
                });
        
        // 创建聚合根
        PaymentOrder order = PaymentOrder.builder()
                .paymentId(PaymentId.generate())
                .merchantOrderId(merchantOrderId)
                .merchantId(merchantId)
                .userId(userId)
                .amount(amount)
                .description(description)
                .channelCode(channelCode)
                .notifyUrl(notifyUrl)
                .returnUrl(returnUrl)
                .extraParams(extraParams)
                .build();
        
        return paymentOrderRepository.save(order);
    }
    
    /**
     * 检查是否可以发起支付
     */
    public boolean canInitiatePayment(PaymentOrder order) {
        return order.getStatus().canClose() == false && 
               (order.getStatus() == com.payment.domain.payment.model.enums.PaymentStatus.CREATED ||
                order.getStatus() == com.payment.domain.payment.model.enums.PaymentStatus.FAILED);
    }
}
