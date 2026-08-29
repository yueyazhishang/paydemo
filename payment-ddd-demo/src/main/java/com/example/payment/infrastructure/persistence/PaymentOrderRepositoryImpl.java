package com.example.payment.infrastructure.persistence;

import com.example.payment.domain.payment.model.PaymentOrder;
import com.example.payment.domain.payment.model.PaymentStatus;
import com.example.payment.domain.payment.repository.PaymentOrderRepository;
import com.example.payment.domain.shared.Channel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 支付单仓储端口实现（领域端口 → JPA 基础设施）。
 */
@Repository
@RequiredArgsConstructor
public class PaymentOrderRepositoryImpl implements PaymentOrderRepository {

    private final PaymentOrderJpaRepository jpaRepository;
    private final PaymentOrderConverter converter;

    @Override
    public PaymentOrder save(PaymentOrder order) {
        // 幂等保存：已存在则基于持久化对象覆盖更新（乐观锁由 @Version 保证并发安全）
        PaymentOrderPO po = jpaRepository.findByPaymentId(order.getPaymentId())
                .orElseGet(() -> converter.toPO(order));
        PaymentOrderPO fresh = converter.toPO(order);
        po.setChannelTradeNo(fresh.getChannelTradeNo());
        po.setPayType(fresh.getPayType());
        po.setPayParams(fresh.getPayParams());
        po.setFailReason(fresh.getFailReason());
        po.setStatus(fresh.getStatus());
        return converter.toDomain(jpaRepository.save(po));
    }

    @Override
    public Optional<PaymentOrder> findByPaymentId(String paymentId) {
        return jpaRepository.findByPaymentId(paymentId).map(converter::toDomain);
    }

    @Override
    public Optional<PaymentOrder> findByBizOrderNoAndChannel(String bizOrderNo, Channel channel) {
        return jpaRepository.findByBizOrderNoAndChannel(bizOrderNo, channel).map(converter::toDomain);
    }

    @Override
    public List<PaymentOrder> findByStatus(PaymentStatus status) {
        return jpaRepository.findByStatus(status).stream().map(converter::toDomain).toList();
    }
}
