package com.example.payment.infrastructure.persistence;

import com.example.payment.domain.payment.model.RefundOrder;
import com.example.payment.domain.shared.Currency;
import com.example.payment.domain.shared.Money;
import org.springframework.stereotype.Component;

/**
 * 退款聚合 ↔ PO 转换器。
 */
@Component
public class RefundOrderConverter {

    public RefundOrderPO toPO(RefundOrder refund) {
        RefundOrderPO po = new RefundOrderPO();
        po.setRefundId(refund.getRefundId());
        po.setPaymentId(refund.getPaymentId());
        po.setRefundAmount(refund.getRefundAmount().getAmountMinor());
        po.setCurrency(refund.getRefundAmount().getCurrency());
        po.setStatus(refund.getStatus());
        po.setChannelRefundNo(refund.getChannelRefundNo());
        po.setReason(refund.getReason());
        return po;
    }

    public RefundOrder toDomain(RefundOrderPO po) {
        return RefundOrder.rehydrate(
                po.getRefundId(), po.getPaymentId(),
                Money.ofMinor(po.getRefundAmount(), po.getCurrency()),
                po.getStatus(), po.getChannelRefundNo(), po.getReason());
    }
}
