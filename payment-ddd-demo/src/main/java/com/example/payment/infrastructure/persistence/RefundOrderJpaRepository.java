package com.example.payment.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 退款单 Spring Data 仓储接口。
 */
public interface RefundOrderJpaRepository extends JpaRepository<RefundOrderPO, Long> {

    Optional<RefundOrderPO> findByRefundId(String refundId);

    List<RefundOrderPO> findByPaymentIdAndStatusIn(String paymentId, List<com.example.payment.domain.payment.model.RefundStatus> statuses);
}
