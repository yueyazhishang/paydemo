package com.zxpay.domain.refund.model;

import com.zxpay.domain.channel.model.ChannelCode;
import com.zxpay.domain.payment.model.FailureInfo;
import com.zxpay.sharedkernel.money.Money;

import java.time.Instant;
import java.util.Optional;

/**
 * 通道退款结果（归一化）。
 *
 * <p>与支付结果分开建模，因为退款有自己特有的状态与字段：
 * <ul>
 *   <li>{@code channelRefundId}：通道侧退款单号，与支付交易号不同，
 *       后续查退款状态要用它。</li>
 *   <li>{@code PROCESSING}：退款已受理但资金在途。卡退款普遍如此，
 *       国内也有「退款申请成功，等待银行处理」的阶段。</li>
 *   <li>{@code refundedAmount}：实际退款金额。部分通道支持
 *       「部分退款时扣除手续费」，实退金额会小于申请金额。</li>
 * </ul>
 */
public record ChannelRefundResult(
        ChannelCode channel,

        /** 我方生成的退款幂等键，回写便于追溯。 */
        String refundIdempotencyKey,

        /** 通道侧退款单号。 */
        String channelRefundId,

        /** 通道原始退款状态字符串。 */
        String rawStatus,

        /** 归一化后的退款状态。 */
        RefundStatus normalizedStatus,

        /** 实退金额。 */
        Money refundedAmount,

        /** 退款完成时间（通道侧）。PROCESSING 时为空。 */
        Instant refundedAt,

        FailureInfo failure,

        Instant respondedAt
) {

    public static ChannelRefundResult processing(ChannelCode channel, String idempotencyKey,
                                                 String channelRefundId, String rawStatus, Instant respondedAt) {
        return new ChannelRefundResult(channel, idempotencyKey, channelRefundId, rawStatus,
                RefundStatus.PROCESSING, null, null, null, respondedAt);
    }

    public static ChannelRefundResult succeeded(ChannelCode channel, String idempotencyKey,
                                                String channelRefundId, String rawStatus,
                                                Money refundedAmount, Instant refundedAt, Instant respondedAt) {
        return new ChannelRefundResult(channel, idempotencyKey, channelRefundId, rawStatus,
                RefundStatus.SUCCEEDED, refundedAmount, refundedAt, null, respondedAt);
    }

    public static ChannelRefundResult failed(ChannelCode channel, String idempotencyKey,
                                             String channelRefundId, String rawStatus,
                                             FailureInfo failure, Instant respondedAt) {
        return new ChannelRefundResult(channel, idempotencyKey, channelRefundId, rawStatus,
                RefundStatus.FAILED, null, null, failure, respondedAt);
    }

    public boolean isSucceeded() {
        return normalizedStatus == RefundStatus.SUCCEEDED;
    }

    public Optional<FailureInfo> failureOptional() {
        return Optional.ofNullable(failure);
    }

    /** 结果未知，需主动查退款单确认。 */
    public boolean requiresQueryBeforeDecision() {
        return failure != null && failure.requiresQueryBeforeDecision();
    }
}
