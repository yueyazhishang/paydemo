package com.example.payment.infrastructure.persistence;

import com.example.payment.domain.shared.Channel;
import com.example.payment.domain.payment.model.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data 仓储接口（基础设施内部，不外泄到领域层）。
 */
public interface PaymentOrderJpaRepository extends JpaRepository<PaymentOrderPO, Long> {

    Optional<PaymentOrderPO> findByPaymentId(String paymentId);

    Optional<PaymentOrderPO> findByBizOrderNoAndChannel(String bizOrderNo, Channel channel);

    List<PaymentOrderPO> findByStatus(PaymentStatus status);
}
