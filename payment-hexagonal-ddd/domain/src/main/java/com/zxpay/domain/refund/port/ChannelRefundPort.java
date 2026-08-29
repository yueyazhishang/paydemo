package com.zxpay.domain.refund.port;

import com.zxpay.domain.channel.model.ChannelCode;
import com.zxpay.domain.payment.model.PaymentOrderId;
import com.zxpay.domain.payment.port.ChannelPort;
import com.zxpay.domain.refund.model.ChannelRefundResult;
import com.zxpay.domain.refund.model.RefundOrderId;
import com.zxpay.sharedkernel.money.Money;

/**
 * 出站端口：向通道发起退款。
 *
 * <p>只由真正支持退款的通道适配器实现。是否支持由 {@code Capability.FULL_REFUND}
 * 声明，不匹配则由 {@code ChannelGatewayRegistry} 解析为空，
 * 上层据此返回明确的「该通道不支持退款」，而不是抛不支持操作异常。
 *
 * <p><b>实现契约：</b>
 * <ol>
 *   <li><b>幂等键必须用传入的 {@code refundIdempotencyKey}</b>。
 *       微信用 out_refund_no、Stripe 用 Idempotency-Key，
 *       都来自这个字段，绝不可适配器自行生成。</li>
 *   <li><b>退款超时按 UNKNOWN 处理</b>。原因与下单超时相同：
 *       很可能通道已受理，直接判失败会导致重复退款。</li>
 *   <li><b>需要证书的通道（微信）由适配器自行加载证书</b>，
 *       领域层不感知证书的存在。</li>
 * </ol>
 */
public interface ChannelRefundPort extends ChannelPort {

    ChannelRefundResult refund(ChannelRefundRequest request);

    record ChannelRefundRequest(
            ChannelCode channel,
            PaymentOrderId paymentOrderId,
            RefundOrderId refundOrderId,

            /** 我方退款幂等键。对应通道侧的 out_refund_no 或 Idempotency-Key。 */
            String refundIdempotencyKey,

            /** 原支付在通道侧的交易号。退款必须关联到它。 */
            String channelTransactionId,

            /** 本次退款金额。 */
            Money refundAmount,

            /** 原支付金额。部分通道要求一并传入以做校验。 */
            Money originalAmount,

            String reason,

            /** 退款结果回调地址。 */
            String notifyUrl
    ) {

        public static ChannelRefundRequest of(ChannelCode channel,
                                              PaymentOrderId paymentOrderId,
                                              RefundOrderId refundOrderId,
                                              String idempotencyKey,
                                              String channelTransactionId,
                                              Money refundAmount,
                                              Money originalAmount,
                                              String reason,
                                              String notifyUrl) {
            return new ChannelRefundRequest(channel, paymentOrderId, refundOrderId, idempotencyKey,
                    channelTransactionId, refundAmount, originalAmount, reason, notifyUrl);
        }
    }
}
