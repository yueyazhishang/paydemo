package com.payment.domain.payment.repository;

import com.payment.domain.payment.model.aggregate.PaymentOrder;
import com.payment.domain.payment.model.enums.PaymentStatus;
import com.payment.domain.payment.model.valueobject.OrderId;
import com.payment.domain.payment.model.valueobject.PaymentId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 支付订单仓储接口
 * 
 * 定义领域层对持久化的抽象，由基础设施层实现
 * 
 * 注意: 这是接口，定义在领域层，实现在基础设施层
 * 这是DDD中依赖倒置原则的体现
 */
public interface PaymentOrderRepository {
    
    /**
     * 保存支付订单
     */
    PaymentOrder save(PaymentOrder order);
    
    /**
     * 根据ID查找
     */
    Optional<PaymentOrder> findById(PaymentId paymentId);
    
    /**
     * 根据商户订单ID查找
     */
    Optional<PaymentOrder> findByMerchantOrderId(String merchantId, OrderId orderId);
    
    /**
     * 根据渠道订单号查找
     */
    Optional<PaymentOrder> findByChannelOrderId(String channelCode, String channelOrderId);
    
    /**
     * 根据状态查找
     */
    List<PaymentOrder> findByStatus(PaymentStatus status);
    
    /**
     * 分页查询
     */
    Page<PaymentOrder> findByMerchantId(String merchantId, Pageable pageable);
    
    /**
     * 查找过期订单
     */
    List<PaymentOrder> findExpiredOrders(PaymentStatus status, LocalDateTime expireBefore);
    
    /**
     * 删除
     */
    void delete(PaymentId paymentId);
}
