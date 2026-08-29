package com.demo.payment.domain.acquiring.service;

import com.demo.payment.domain.acquiring.model.aggregate.PaymentOrder;
import com.demo.payment.domain.channel.model.ChannelCapability;
import com.demo.payment.shared.money.Money;

import java.time.Duration;
import java.time.Instant;

/**
 * 退款策略默认实现。
 *
 * <p>这里集中了退款的全部前置校验规则。把它们集中在一处（而不是散落在 Controller、
 * Service、Adapter 各处）的价值在于：<b>规则是可见、可测、可演进的</b>。
 */
public class RefundPolicyServiceImpl implements RefundPolicyService {

    @Override
    public RefundCheckResult check(PaymentOrder order, ChannelCapability capability, Money amount) {
        // 规则一：订单必须已支付
        if (!order.status().isPaid()) {
            return RefundCheckResult.reject("订单未支付成功，当前状态：" + order.status());
        }

        // 规则二：金额必须为正且不超过原单金额
        if (!amount.isPositive()) {
            return RefundCheckResult.reject("退款金额必须大于 0");
        }
        if (amount.isGreaterThan(order.amount())) {
            return RefundCheckResult.reject("退款金额超过原订单金额");
        }

        // 规则三：币种必须一致（跨币种退款涉及汇率，属于另一个业务域）
        if (!amount.currency().equals(order.amount().currency())) {
            return RefundCheckResult.reject("退款币种与原订单不一致");
        }

        // 规则四：部分退款能力
        boolean isPartial = amount.isLessThan(order.amount());
        if (isPartial && !capability.supportsPartialRefund()) {
            return RefundCheckResult.reject("通道 " + capability.channelCode() + " 不支持部分退款");
        }

        // 规则五：多次部分退款能力
        boolean hasPreviousRefund = !order.totalRefunded().isZero();
        if (isPartial && hasPreviousRefund && !capability.supportsMultiplePartialRefund()) {
            return RefundCheckResult.reject("通道 " + capability.channelCode() + " 不支持多次部分退款");
        }

        // 规则六：退款期限（通道能力决定，Antom 的 BNPL 类只有 90~120 天）
        Integer window = capability.refundWindowDays();
        if (window != null) {
            long days = Duration.between(order.createdAt(), Instant.now()).toDays();
            if (!capability.isRefundableAfterDays((int) days)) {
                return RefundCheckResult.reject("超出通道退款期限：" + days + " 天 > " + window + " 天，需走人工差错流程");
            }
        }

        // 规则七：累计不超额（最终防线，与聚合内的校验形成双保险）
        if (order.totalRefunded().plus(amount).isGreaterThan(order.amount())) {
            return RefundCheckResult.reject("累计退款将超过原订单金额，已退："
                    + order.totalRefunded() + "，本次：" + amount);
        }

        return RefundCheckResult.ok();
    }
}
