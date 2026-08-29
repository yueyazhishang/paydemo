package com.zxpay.domain.payment.port;

import com.zxpay.domain.channel.model.ChannelCode;
import com.zxpay.domain.payment.model.ChannelResult;
import com.zxpay.domain.payment.model.PaymentAttemptId;
import com.zxpay.domain.payment.model.PaymentOrderId;

/**
 * 出站端口：撤销授权（Void / 取消预授权）。
 *
 * <p>用于<b>已授权但尚未请款</b>的场景：把冻结的额度释放掉。
 *
 * <p>与退款的本质区别（务必分清）：
 * <table border="1">
 *   <tr><th></th><th>Void（撤销授权）</th><th>Refund（退款）</th></tr>
 *   <tr><td>资金状态</td><td>仅冻结，未划转</td><td>已划转，需退回</td></tr>
 *   <tr><td>是否产生账务流水</td><td>否</td><td>是（一进一出）</td></tr>
 *   <tr><td>通常时效</td><td>即时释放</td><td>1~15 个工作日到账</td></tr>
 *   <tr><td>对账表现</td><td>交易消失</td><td>出现一条负数流水</td></tr>
 * </table>
 *
 * <p>调用错了会直接报错：对未请款的授权调退款，通道会返回「交易不存在」；
 * 对已请款的交易调 void，则钱收不回来却释放了额度。
 */
public interface ChannelVoidPort extends ChannelPort {

    ChannelResult voidAuthorization(ChannelVoidRequest request);

    record ChannelVoidRequest(
            ChannelCode channel,
            PaymentOrderId orderId,
            PaymentAttemptId attemptId,

            /** 通道侧授权标识。 */
            String channelAuthorizationId,

            /** 撤销幂等键。 */
            String voidIdempotencyKey
    ) {

        public static ChannelVoidRequest of(ChannelCode channel,
                                            PaymentOrderId orderId,
                                            PaymentAttemptId attemptId,
                                            String authorizationId,
                                            String idempotencyKey) {
            return new ChannelVoidRequest(channel, orderId, attemptId, authorizationId, idempotencyKey);
        }
    }
}
