package com.zxpay.domain.payment.model;

import com.zxpay.domain.channel.model.ChannelCode;
import com.zxpay.sharedkernel.money.Money;

import java.time.Instant;
import java.util.Optional;

/**
 * 通道响应：<b>归一化结果</b>。
 *
 * <p>适配器把各家千奇百怪的响应翻译成这个对象。上层只见
 * {@code normalizedStatus} 与 {@code interaction}，不见任何通道专属字段。
 *
 * <p>{@code rawStatus} 与 {@code failure} 保留原始细节，写入支付尝试记录，
 * 供对账、客服与差错处理使用。
 */
public record ChannelResult(
        ChannelCode channel,
        PaymentAttemptId attemptId,

        /** 本次使用的通道幂等键，回写以便追溯。 */
        String idempotencyKey,

        /** 通道交易流水号（微信 transaction_id、Stripe charge id）。退款与对账以此为准。 */
        String channelTransactionId,

        /** 通道侧订单号（微信 out_trade_no 回显、Stripe PaymentIntent id）。 */
        String channelOrderNo,

        /** 原始状态 + 归一化状态，双轨保留。 */
        ChannelRawStatus rawStatus,

        /** 归一化后的状态。上层唯一应当依赖的状态字段。 */
        PaymentStatus normalizedStatus,

        /** 前端需要的唤起参数。 */
        ChannelInteraction interaction,

        /** 实扣金额。部分场景（如外币结算）可能与请求金额不同。 */
        Money paidAmount,

        /** 支付完成时间（通道侧）。 */
        Instant paidAt,

        /** 授权信息。仅 auth 模式下非空。 */
        Authorization authorization,

        /** 失败信息。成功时为空。 */
        FailureInfo failure,

        /** 我方收到响应的时间。 */
        Instant respondedAt
) {

    // ---------- 成功构造 ----------

    public static ChannelResult pending(ChannelCode channel,
                                        PaymentAttemptId attemptId,
                                        String idempotencyKey,
                                        String channelOrderNo,
                                        ChannelRawStatus rawStatus,
                                        ChannelInteraction interaction,
                                        Instant respondedAt) {
        return new ChannelResult(channel, attemptId, idempotencyKey, null, channelOrderNo,
                rawStatus, rawStatus.normalized(), interaction, null, null, null, null, respondedAt);
    }

    public static ChannelResult succeeded(ChannelCode channel,
                                          PaymentAttemptId attemptId,
                                          String idempotencyKey,
                                          String channelTransactionId,
                                          String channelOrderNo,
                                          ChannelRawStatus rawStatus,
                                          Money paidAmount,
                                          Instant paidAt,
                                          Instant respondedAt) {
        return new ChannelResult(channel, attemptId, idempotencyKey, channelTransactionId, channelOrderNo,
                rawStatus, PaymentStatus.SUCCEEDED, ChannelInteraction.none(),
                paidAmount, paidAt, null, null, respondedAt);
    }

    public static ChannelResult authorized(ChannelCode channel,
                                           PaymentAttemptId attemptId,
                                           String idempotencyKey,
                                           String channelOrderNo,
                                           ChannelRawStatus rawStatus,
                                           Authorization authorization,
                                           Instant respondedAt) {
        return new ChannelResult(channel, attemptId, idempotencyKey, null, channelOrderNo,
                rawStatus, PaymentStatus.AUTHORIZED, ChannelInteraction.none(),
                null, null, authorization, null, respondedAt);
    }

    public static ChannelResult failed(ChannelCode channel,
                                       PaymentAttemptId attemptId,
                                       String idempotencyKey,
                                       String channelOrderNo,
                                       ChannelRawStatus rawStatus,
                                       FailureInfo failure,
                                       Instant respondedAt) {
        return new ChannelResult(channel, attemptId, idempotencyKey, null, channelOrderNo,
                rawStatus, PaymentStatus.FAILED, ChannelInteraction.none(),
                null, null, null, failure, respondedAt);
    }

    // ---------- 判断 ----------

    public boolean isSucceeded() {
        return normalizedStatus == PaymentStatus.SUCCEEDED;
    }

    public boolean isAuthorized() {
        return normalizedStatus == PaymentStatus.AUTHORIZED;
    }

    public boolean isFailed() {
        return normalizedStatus == PaymentStatus.FAILED;
    }

    public boolean isPending() {
        return normalizedStatus != null && normalizedStatus.isPending();
    }

    public Optional<FailureInfo> failureOptional() {
        return Optional.ofNullable(failure);
    }

    public Optional<Authorization> authorizationOptional() {
        return Optional.ofNullable(authorization);
    }

    /** 是否需要先查单才能定论（结果未知的超时场景）。 */
    public boolean requiresQueryBeforeDecision() {
        return failure != null && failure.requiresQueryBeforeDecision();
    }
}
