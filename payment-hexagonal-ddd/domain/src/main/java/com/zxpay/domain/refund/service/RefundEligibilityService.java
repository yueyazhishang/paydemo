package com.zxpay.domain.refund.service;

import com.zxpay.domain.channel.model.Capability;
import com.zxpay.domain.channel.model.ChannelCapability;
import com.zxpay.domain.channel.model.RefundPolicy;
import com.zxpay.domain.payment.model.PaymentOrder;
import com.zxpay.sharedkernel.money.Money;

import java.time.Instant;
import java.util.Optional;

/**
 * 退款资格校验服务。
 *
 * <p>在<b>创建退款单之前</b>就把不合法的请求挡掉，而不是打到通道再拿一个错误码。
 * 退款失败的成本远高于校验成本：一次失败的退款意味着客服介入、用户投诉、
 * 可能还有资金挂账。
 *
 * <p>校验分三层：
 * <ol>
 *   <li><b>订单层</b>：订单是否已支付、剩余可退金额是否足够。</li>
 *   <li><b>通道层</b>：{@link RefundPolicy} 声明的窗口、次数、部分退款支持等硬约束。</li>
 *   <li><b>业务层</b>：结算后能否退款（涉及垫资风险）。</li>
 * </ol>
 *
 * <p>无状态纯函数，不依赖任何端口，因此极易单测——
 * 这也是把规则从应用层的 if 堆里抽出来的直接收益。
 */
public final class RefundEligibilityService {

    private RefundEligibilityService() {
    }

    /**
     * @return 空表示可退；非空为不可退原因，直接返回给商户与运营
     */
    public static Optional<String> check(PaymentOrder order,
                                         Money refundAmount,
                                         ChannelCapability capability,
                                         boolean settled,
                                         Instant now) {
        if (order == null) {
            return Optional.of("payment order not found");
        }
        if (!order.status().isPaid()) {
            return Optional.of("payment is not paid, current status: " + order.status());
        }
        if (refundAmount == null || !refundAmount.isPositive()) {
            return Optional.of("refund amount must be positive: " + refundAmount);
        }
        if (refundAmount.currency() != order.paidAmount().currency()) {
            return Optional.of("refund currency " + refundAmount.currency()
                    + " differs from paid currency " + order.paidAmount().currency());
        }
        if (refundAmount.isGreaterThan(order.remainingRefundable())) {
            return Optional.of("refund amount " + refundAmount
                    + " exceeds remaining refundable " + order.remainingRefundable());
        }
        if (capability == null) {
            return Optional.of("channel capability not found for " + order.currentChannel());
        }
        if (!capability.supports(Capability.FULL_REFUND)) {
            return Optional.of("channel " + capability.channel() + " does not support refund");
        }
        if (refundAmount.isLessThan(order.paidAmount())
                && !capability.supports(Capability.PARTIAL_REFUND)) {
            return Optional.of("channel " + capability.channel() + " does not support partial refund");
        }

        RefundPolicy policy = capability.refundPolicy();
        return policy.checkRefundable(new RefundPolicy.RefundCheckInput(
                refundAmount,
                order.paidAmount(),
                order.remainingRefundable(),
                order.partialRefundCount(),
                settled,
                order.paidAt(),
                now));
    }
}
