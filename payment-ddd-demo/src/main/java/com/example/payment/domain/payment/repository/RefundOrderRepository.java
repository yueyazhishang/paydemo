package com.example.payment.domain.payment.repository;

import com.example.payment.domain.payment.model.RefundOrder;
import com.example.payment.domain.payment.model.RefundStatus;

import java.util.List;
import java.util.Optional;

/**
 * 退款单仓储接口。
 */
public interface RefundOrderRepository {

    RefundOrder save(RefundOrder refund);

    Optional<RefundOrder> findByRefundId(String refundId);

    /** 查询某支付单下处于未终态的退款单（计算可退金额用） */
    List<RefundOrder> findByPaymentIdAndStatusIn(String paymentId, List<RefundStatus> statuses);
}
