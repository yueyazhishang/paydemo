package com.example.payment.domain.service;

import com.example.payment.domain.payment.model.PaymentOrder;
import com.example.payment.domain.payment.model.RefundOrder;
import com.example.payment.domain.payment.model.RefundStatus;
import com.example.payment.domain.shared.Money;

import java.util.List;

/**
 * 退款领域服务：跨聚合业务规则 —— 可退金额校验。
 * 可退金额 = 已支付金额 − 已退/退款中金额。规则跨 PaymentOrder 与 RefundOrder 两个聚合，
 * 故按 DDD 惯例放在领域服务而非聚合内。
 */
public class RefundDomainService {

    private RefundDomainService() {
    }

    /**
     * 校验可退金额并创建退款聚合（不落库、不提交渠道，仅领域构造）。
     *
     * @param payment       原支付单（必须 SUCCESS）
     * @param activeRefunds 原支付单下所有未终态(SUBMITTED)及成功(SUCCESS)的退款单
     * @param refundAmount  本次退款金额
     * @param reason        退款原因
     */
    public static RefundOrder createRefund(PaymentOrder payment,
                                           List<RefundOrder> activeRefunds,
                                           Money refundAmount,
                                           String reason) {
        if (payment.getStatus() != com.example.payment.domain.payment.model.PaymentStatus.SUCCESS) {
            throw new IllegalStateException("支付单未成功，不允许退款: " + payment.getPaymentId());
        }
        if (!refundAmount.getCurrency().equals(payment.getAmount().getCurrency())) {
            throw new IllegalArgumentException("退款币种与支付币种不一致");
        }

        Money refunded = activeRefunds.stream()
                .filter(r -> r.getStatus() == RefundStatus.SUCCESS || r.getStatus() == RefundStatus.SUBMITTED)
                .map(RefundOrder::getRefundAmount)
                .reduce(Money.ofMinor(0, refundAmount.getCurrency()), Money::add);

        Money refundable = payment.getAmount().subtract(refunded);
        if (!refundAmount.isGreaterThanOrEqual(Money.ofMinor(1, refundAmount.getCurrency()))) {
            throw new IllegalStateException("退款金额必须大于零");
        }
        if (refundable.subtract(refundAmount).getAmountMinor() < 0) {
            throw new IllegalStateException(
                    String.format("超出可退金额: 可退 %s，本次申请 %s", refundable, refundAmount));
        }
        return RefundOrder.create(payment.getPaymentId(), refundAmount, reason);
    }
}
