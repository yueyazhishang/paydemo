package com.payment.domain.refund.repository;

import com.payment.domain.payment.model.valueobject.PaymentId;
import com.payment.domain.payment.model.valueobject.RefundId;
import com.payment.domain.refund.model.aggregate.RefundOrder;
import com.payment.domain.refund.model.enums.RefundStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

/**
 * 退款订单仓储接口
 */
public interface RefundOrderRepository {
    
    /**
     * 保存退款订单
     */
    RefundOrder save(RefundOrder order);
    
    /**
     * 根据ID查找
     */
    Optional<RefundOrder> findById(RefundId refundId);
    
    /**
     * 根据支付订单ID查找所有退款
     */
    List<RefundOrder> findByPaymentId(PaymentId paymentId);
    
    /**
     * 根据状态查找
     */
    List<RefundOrder> findByStatus(RefundStatus status);
    
    /**
     * 分页查询
     */
    Page<RefundOrder> findByMerchantId(String merchantId, Pageable pageable);
    
    /**
     * 根据渠道退款单号查找
     */
    Optional<RefundOrder> findByChannelRefundId(String channelCode, String channelRefundId);
}
