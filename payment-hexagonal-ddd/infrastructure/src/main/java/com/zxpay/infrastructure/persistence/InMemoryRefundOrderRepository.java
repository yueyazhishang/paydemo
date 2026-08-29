package com.zxpay.infrastructure.persistence;

import com.zxpay.domain.merchant.model.MerchantAppId;
import com.zxpay.domain.payment.model.PaymentOrderId;
import com.zxpay.domain.refund.model.RefundOrder;
import com.zxpay.domain.refund.model.RefundOrderId;
import com.zxpay.domain.refund.model.RefundStatus;
import com.zxpay.domain.refund.port.RefundOrderRepository;
import com.zxpay.sharedkernel.exception.ConcurrencyConflictException;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 退款单仓储的内存实现。
 *
 * <p>注意 {@code findByPaymentOrderId}：它是「退款独立成聚合」后
 * 用来计算累计退款的入口。
 *
 * <p>这里有个容易被忽略的性能陷阱：如果每笔退款都查全量退款单再求和，
 * 退款次数多了会变慢。生产做法是在支付单上冗余
 * {@code refunded_amount} 字段（正如本 Demo 中 {@code PaymentOrder} 所做的），
 * 查询时直接读冗余值，只有在需要明细时才查退款单列表。
 * 这也是「跨聚合一致性用冗余字段维护」的典型手法。
 */
@Repository
public class InMemoryRefundOrderRepository implements RefundOrderRepository {

    private final Map<RefundOrderId, RefundOrder> store = new ConcurrentHashMap<>();
    private final Map<String, RefundOrderId> merchantRefundIndex = new ConcurrentHashMap<>();

    @Override
    public Optional<RefundOrder> findById(RefundOrderId refundId) {
        return Optional.ofNullable(store.get(refundId));
    }

    @Override
    public Optional<RefundOrder> findByMerchantRefundNo(MerchantAppId appId, String merchantRefundNo) {
        RefundOrderId id = merchantRefundIndex.get(appId.value() + ":" + merchantRefundNo);
        return id == null ? Optional.empty() : findById(id);
    }

    @Override
    public List<RefundOrder> findByPaymentOrderId(PaymentOrderId paymentOrderId) {
        List<RefundOrder> result = new ArrayList<>();
        for (RefundOrder order : store.values()) {
            if (order.paymentOrderId().equals(paymentOrderId)) {
                result.add(order);
            }
        }
        return result;
    }

    @Override
    public synchronized void save(RefundOrder refundOrder) {
        RefundOrder existing = store.get(refundOrder.id());
        if (existing != null && existing.version() != refundOrder.version()) {
            throw new ConcurrencyConflictException("RefundOrder", refundOrder.id().value());
        }
        refundOrder.assignVersion(existing == null ? 1L : existing.version() + 1L);
        store.put(refundOrder.id(), refundOrder);
        merchantRefundIndex.put(refundOrder.appId().value() + ":" + refundOrder.merchantRefundNo(),
                refundOrder.id());
    }

    @Override
    public List<RefundOrder> findPendingBefore(List<RefundStatus> statuses, Instant before, int limit) {
        List<RefundOrder> result = new ArrayList<>();
        for (RefundOrder order : store.values()) {
            if (result.size() >= limit) {
                break;
            }
            if (statuses.contains(order.status()) && order.updatedAt().isBefore(before)) {
                result.add(order);
            }
        }
        return result;
    }
}
