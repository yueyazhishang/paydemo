package com.zxpay.domain.channel.model;

import com.zxpay.sharedkernel.money.Money;
import com.zxpay.sharedkernel.time.ClockHolder;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * 退款能力约束。
 *
 * <p>退款是支付系统里最复杂的逆向流程，各通道规则差异极大，必须在下单与退款前就校验：
 *
 * <ul>
 *   <li><b>退款窗口</b>：微信/支付宝通常允许交易成功后 1 年内退款；卡组织普遍是 180 天，
 *       超期后无法发起退款，只能走线下转账——这在财务上是完全不同的两条流程。</li>
 *   <li><b>部分退款次数</b>：国内通道常见「最多 50 次部分退款」；海外部分 PSP 不限次数，
 *       只要累计不超过原额。同样是「以金额为准」还是「以次数为准」的差异。</li>
 *   <li><b>需证书</b>：微信退款必须带商户证书，且退款回调里的敏感字段需 AEAD 解密。</li>
 *   <li><b>原路退回</b>：国内基本强制原路退回；PayPal 等钱包可退到账户余额，用户侧体验不同。</li>
 *   <li><b>结算后退款</b>：资金已结算给商户后再退款需要垫付，涉及垫资风险与额外审批。</li>
 * </ul>
 *
 * <p>本类是纯数据与单条规则判断，跨步骤的综合校验放在 refund 上下文的
 * {@code RefundEligibilityService}，避免这里越权访问支付单内部状态。
 */
public record RefundPolicy(
        boolean partialRefundSupported,

        /** 最大部分退款次数。{@link #UNLIMITED} 表示不限次数（以累计金额为准）。 */
        int maxPartialRefundCount,

        /** 退款时间窗口（自支付成功起算）。null 表示不限制。 */
        Duration refundWindow,

        /** 退款请求是否同步返回最终结果。false 表示必须等异步通知或主动查询。 */
        boolean instantRefund,

        /** 是否需要商户证书（微信退款为 true）。 */
        boolean requiresCertificate,

        /** 是否强制原路退回。false 表示可退至用户在该通道的账户余额。 */
        boolean originalMethodOnly,

        /** 资金已结算给商户后是否仍支持退款。 */
        boolean supportsRefundAfterSettlement
) {

    public static final int UNLIMITED = Integer.MAX_VALUE;

    /** 是否已超出退款窗口。 */
    public boolean isExpired(Instant paidAt, Instant now) {
        if (refundWindow == null || paidAt == null) {
            return false;
        }
        return !now.isBefore(paidAt.plus(refundWindow));
    }

    public boolean isExpired(Instant paidAt) {
        return isExpired(paidAt, ClockHolder.now());
    }

    /** 是否还能再发起一次部分退款。 */
    public boolean hasQuota(int usedPartialRefundCount) {
        return usedPartialRefundCount < maxPartialRefundCount;
    }

    /**
     * 校验本次退款是否被通道能力允许。
     *
     * <p>只判断「通道层面能不能退」，不判断「这笔订单该不该退」——后者属于业务规则。
     *
     * @return 空表示通过；非空为不可退原因，可直接写入退款失败记录与运营看板
     */
    public Optional<String> checkRefundable(RefundCheckInput input) {
        Money amount = input.refundAmount();
        Money original = input.originalAmount();

        if (amount.currency() != original.currency()) {
            return Optional.of("currency mismatch between refund and original payment");
        }
        if (amount.isGreaterThan(input.remainingRefundable())) {
            return Optional.of("refund amount exceeds remaining refundable: " + input.remainingRefundable());
        }
        if (isExpired(input.paidAt(), input.now())) {
            return Optional.of("refund window expired: " + refundWindow);
        }
        if (amount.isLessThan(original) && !partialRefundSupported) {
            return Optional.of("partial refund not supported by channel");
        }
        if (amount.isLessThan(original) && !hasQuota(input.usedPartialRefundCount())) {
            return Optional.of("partial refund count exceeded: " + maxPartialRefundCount);
        }
        if (input.settled() && !supportsRefundAfterSettlement) {
            return Optional.of("refund after settlement not supported by channel");
        }
        return Optional.empty();
    }

    /** 退款校验入参。用 record 聚合上下文，避免方法签名随规则增长而爆炸。 */
    public record RefundCheckInput(
            /** 本次拟退金额。 */
            Money refundAmount,

            /** 原支付金额。 */
            Money originalAmount,

            /** 仍可退金额（原额 - 已退成功 - 退款中）。 */
            Money remainingRefundable,

            /** 已发生的部分退款次数。 */
            int usedPartialRefundCount,

            /** 资金是否已结算给商户。 */
            boolean settled,

            /** 原支付成功时间，用于计算退款窗口。 */
            Instant paidAt,

            /** 当前时间，显式传入以保证规则可测。 */
            Instant now
    ) {

        public static RefundCheckInput now(Money refundAmount,
                                           Money originalAmount,
                                           Money remainingRefundable,
                                           int usedPartialRefundCount,
                                           boolean settled,
                                           Instant paidAt) {
            return new RefundCheckInput(refundAmount, originalAmount, remainingRefundable,
                    usedPartialRefundCount, settled, paidAt, ClockHolder.now());
        }
    }
}
