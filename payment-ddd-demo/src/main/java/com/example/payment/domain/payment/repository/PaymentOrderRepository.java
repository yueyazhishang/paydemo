package com.example.payment.domain.payment.repository;

import com.example.payment.domain.payment.model.PaymentOrder;
import com.example.payment.domain.payment.model.PaymentStatus;
import com.example.payment.domain.shared.Channel;

import java.util.List;
import java.util.Optional;

/**
 * 支付单仓储接口（领域层定义，基础设施层以 JPA 实现）。
 */
public interface PaymentOrderRepository {

    PaymentOrder save(PaymentOrder order);

    Optional<PaymentOrder> findByPaymentId(String paymentId);

    Optional<PaymentOrder> findByBizOrderNoAndChannel(String bizOrderNo, Channel channel);

    /** 查单兜底定时任务使用：捞取超时未终态的支付单 */
    List<PaymentOrder> findByStatus(PaymentStatus status);
}
