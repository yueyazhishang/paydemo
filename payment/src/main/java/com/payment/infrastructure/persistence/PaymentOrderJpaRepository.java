package com.payment.infrastructure.persistence;

import com.payment.domain.payment.model.aggregate.PaymentOrder;
import com.payment.domain.payment.model.enums.PaymentStatus;
import com.payment.domain.payment.model.valueobject.OrderId;
import com.payment.domain.payment.model.valueobject.PaymentId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA 支付订单仓储接口
 */
@Repository
public interface PaymentOrderJpaRepository extends JpaRepository<PaymentOrder, String> {
    
    Optional<PaymentOrder> findByPaymentId(PaymentId paymentId);
    
    Optional<PaymentOrder> findByMerchantIdAndMerchantOrderId(String merchantId, OrderId orderId);
    
    List<PaymentOrder> findByStatus(PaymentStatus status);
    
    Page<PaymentOrder> findByMerchantId(String merchantId, Pageable pageable);
    
    List<PaymentOrder> findByStatusAndExpireTimeBefore(PaymentStatus status, LocalDateTime expireTime);
}
