package com.zxpay.domain.payment.port;

import com.zxpay.domain.channel.model.ChannelCode;
import com.zxpay.domain.payment.model.ChannelResult;
import com.zxpay.domain.payment.model.PaymentAttemptId;
import com.zxpay.domain.payment.model.PaymentOrderId;

/**
 * 出站端口：撤销交易（Reverse / 冲正）。
 *
 * <p><b>国内支付特色能力，海外卡体系里没有直接对应物</b>——这是最容易做错映射的地方。
 *
 * <p>微信支付的「撤销」与「退款」区别：
 * <ul>
 *   <li><b>撤销</b>：针对当天的交易，调用后若已支付则原路退回（免手续费、即时），
 *       若未支付则直接关闭订单。是「支付结果不确定」时的安全收尾动作。</li>
 *   <li><b>退款</b>：针对已确定成功的交易，走退款流程，有退款窗口限制。</li>
 * </ul>
 *
 * <p>海外要达成类似效果，必须区分：
 * <ul>
 *   <li>未请款 → {@link ChannelVoidPort}（撤销授权）</li>
 *   <li>已请款 → 退款（且受 180 天窗口限制）</li>
 * </ul>
 * 所以「撤销」这个动作在不同市场上要映射到不同端口，
 * 这正是能力矩阵需要 {@code REVERSE} 位的原因。
 *
 * <p>典型使用场景：付款码支付超时未出结果时，
 * 收银员发起撤销，避免「不确定是否已扣款」带来的资金风险。
 */
public interface ChannelReversePort extends ChannelPort {

    ChannelResult reverse(ChannelReverseRequest request);

    record ChannelReverseRequest(
            ChannelCode channel,
            PaymentOrderId orderId,
            PaymentAttemptId attemptId,
            String merchantOrderNo,
            String channelTransactionId,
            String reverseIdempotencyKey
    ) {

        public static ChannelReverseRequest of(ChannelCode channel,
                                               PaymentOrderId orderId,
                                               PaymentAttemptId attemptId,
                                               String merchantOrderNo,
                                               String idempotencyKey) {
            return new ChannelReverseRequest(channel, orderId, attemptId, merchantOrderNo, null, idempotencyKey);
        }
    }
}
