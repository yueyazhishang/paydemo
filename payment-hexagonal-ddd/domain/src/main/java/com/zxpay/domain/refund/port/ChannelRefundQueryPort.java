package com.zxpay.domain.refund.port;

import com.zxpay.domain.channel.model.ChannelCode;
import com.zxpay.domain.payment.model.PaymentOrderId;
import com.zxpay.domain.payment.port.ChannelPort;
import com.zxpay.domain.refund.model.ChannelRefundResult;
import com.zxpay.domain.refund.model.RefundOrderId;

/**
 * 出站端口：主动查询退款结果。
 *
 * <p>与支付查单同理：<b>退款通知同样会丢失</b>，必须有主动查询兜底。
 *
 * <p>退款查单甚至比支付查单更重要，原因是退款的终态影响面更大：
 * 退款通知丢了，系统会一直认为「退款中」，
 * 商户不敢重新发货，用户等不到钱，客服工单堆积。
 * 而卡组织退款本身就要 5~10 个工作日，
 * 如果没有主动查询，这个「等待」会无限期延长。
 */
public interface ChannelRefundQueryPort extends ChannelPort {

    ChannelRefundResult queryRefund(ChannelRefundQueryRequest request);

    record ChannelRefundQueryRequest(
            ChannelCode channel,
            PaymentOrderId paymentOrderId,
            RefundOrderId refundOrderId,

            /** 我方退款幂等键。多数通道可用它反查。 */
            String refundIdempotencyKey,

            /** 通道侧退款单号。已拿到则优先使用，查询最精确。 */
            String channelRefundId,

            String channelTransactionId
    ) {

        public static ChannelRefundQueryRequest byIdempotencyKey(ChannelCode channel,
                                                                PaymentOrderId paymentOrderId,
                                                                RefundOrderId refundOrderId,
                                                                String idempotencyKey,
                                                                String channelTransactionId) {
            return new ChannelRefundQueryRequest(channel, paymentOrderId, refundOrderId,
                    idempotencyKey, null, channelTransactionId);
        }
    }
}
