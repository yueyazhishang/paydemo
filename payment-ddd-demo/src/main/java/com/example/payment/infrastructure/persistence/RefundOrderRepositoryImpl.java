package com.example.payment.infrastructure.persistence;

import com.example.payment.domain.payment.model.RefundOrder;
import com.example.payment.domain.payment.model.RefundStatus;
import com.example.payment.domain.payment.repository.RefundOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 退款单仓储端口实现。
 */
@Repository
@RequiredArgsConstructor
public class RefundOrderRepositoryImpl implements RefundOrderRepository {

    private final RefundOrderJpaRepository jpaRepository;
    private final RefundOrderConverter converter;

    @Override
    public RefundOrder save(RefundOrder refund) {
        RefundOrderPO po = jpaRepository.findByRefundId(refund.getRefundId())
                .orElseGet(() -> converter.toPO(refund));
        RefundOrderPO fresh = converter.toPO(refund);
        po.setStatus(fresh.getStatus());
        po.setChannelRefundNo(fresh.getChannelRefundNo());
        return converter.toDomain(jpaRepository.save(po));
    }

    @Override
    public Optional<RefundOrder> findByRefundId(String refundId) {
        return jpaRepository.findByRefundId(refundId).map(converter::toDomain);
    }

    @Override
    public List<RefundOrder> findByPaymentIdAndStatusIn(String paymentId, List<RefundStatus> statuses) {
        return jpaRepository.findByPaymentIdAndStatusIn(paymentId, statuses).stream()
                .map(converter::toDomain).toList();
    }
}
