package com.zxpay.domain.payment.port;

import com.zxpay.domain.channel.model.ChannelCode;
import com.zxpay.domain.payment.model.ChannelResult;
import com.zxpay.domain.payment.model.PaymentAttemptId;
import com.zxpay.domain.payment.model.PaymentOrderId;

/**
 * 出站端口：关闭未支付订单。
 *
 * <p>用于用户放弃支付、订单超时、商户主动取消等场景。
 * 关闭后通道侧不再受理该订单的支付，用户扫码会提示订单已失效。
 *
 * <p><b>关键约束：只能关闭未支付的订单。</b>
 * 已支付的订单要「反悔」必须走退款流程，不能关闭——
 * 否则会出现「钱已收、订单已关」的账务黑洞。
 * 状态机在 {@code PaymentStateMachine} 中会拦截这种非法转移。
 *
 * <p>注意：通道侧的关闭通常是异步生效的，且部分通道（如银行转账类）
 * 根本没有「关闭」这个概念，只能等订单自然过期。
 * 这类差异由 {@code Capability.ORDER_CLOSE} 声明。
 */
public interface ChannelClosePort extends ChannelPort {

    ChannelResult close(ChannelCloseRequest request);

    record ChannelCloseRequest(
            ChannelCode channel,
            PaymentOrderId orderId,
            PaymentAttemptId attemptId,

            /** 商户订单号。国内通道以此关闭。 */
            String merchantOrderNo,

            /** 通道侧订单号。若已拿到则优先使用。 */
            String channelOrderNo,

            /** 关闭幂等键。 */
            String closeIdempotencyKey
    ) {

        public static ChannelCloseRequest of(ChannelCode channel,
                                             PaymentOrderId orderId,
                                             PaymentAttemptId attemptId,
                                             String merchantOrderNo,
                                             String idempotencyKey) {
            return new ChannelCloseRequest(channel, orderId, attemptId, merchantOrderNo, null, idempotencyKey);
        }
    }
}
