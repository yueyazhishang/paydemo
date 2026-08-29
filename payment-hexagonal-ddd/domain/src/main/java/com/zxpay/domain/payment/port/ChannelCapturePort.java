package com.zxpay.domain.payment.port;

import com.zxpay.domain.channel.model.ChannelCode;
import com.zxpay.domain.payment.model.ChannelResult;
import com.zxpay.domain.payment.model.PaymentAttemptId;
import com.zxpay.domain.payment.model.PaymentOrderId;

import java.time.Instant;

/**
 * 出站端口：请款（Capture）。
 *
 * <p>对已授权的金额执行实际扣款。这是海外卡支付的标准动作，
 * 国内第三方支付通常没有对应的独立步骤（下单即扣款）。
 *
 * <p>只支持国内通道的系统在扩展到海外时，最容易在这里出问题：
 * 把「支付成功」等同于「钱到账」，于是忽略请款环节，
 * 结果是用户额度被冻结了、商户却始终没收到钱，
 * 7 天后授权自动释放，订单变成一笔「幽灵交易」。
 *
 * <p>请款需要独立幂等键：授权、请款、部分请款是三次不同的通道调用，
 * 每次都要能安全重试。
 */
public interface ChannelCapturePort extends ChannelPort {

    ChannelResult capture(ChannelCaptureRequest request);

    record ChannelCaptureRequest(
            ChannelCode channel,
            PaymentOrderId orderId,
            PaymentAttemptId attemptId,

            /** 通道侧授权标识。 */
            String channelAuthorizationId,

            /** 授权标识（部分通道用独立字段）。授权与请款在不同通道里命名混乱，此处统一。 */
            String channelOrderNo,

            /** 本次请款金额。可小于授权金额（部分请款）。 */
            com.zxpay.sharedkernel.money.Money amount,

            /** 请款幂等键。多次部分请款必须各不相同，重试必须相同。 */
            String captureIdempotencyKey,

            Instant requestedAt
    ) {

        public static ChannelCaptureRequest of(ChannelCode channel,
                                               PaymentOrderId orderId,
                                               PaymentAttemptId attemptId,
                                               String authorizationId,
                                               com.zxpay.sharedkernel.money.Money amount,
                                               String idempotencyKey) {
            return new ChannelCaptureRequest(channel, orderId, attemptId, authorizationId, null,
                    amount, idempotencyKey, Instant.now());
        }
    }
}
