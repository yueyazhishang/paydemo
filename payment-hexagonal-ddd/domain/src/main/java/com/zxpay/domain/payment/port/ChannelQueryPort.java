package com.zxpay.domain.payment.port;

import com.zxpay.domain.channel.model.ChannelCode;
import com.zxpay.domain.payment.model.ChannelResult;
import com.zxpay.domain.payment.model.PaymentAttemptId;
import com.zxpay.domain.payment.model.PaymentOrderId;

/**
 * 出站端口：主动查询通道侧订单状态。
 *
 * <p><b>这是支付系统最重要的一条兜底链路</b>，不是可选功能。
 *
 * <p>为什么必需？通道的异步通知<b>一定会丢</b>：
 * <ul>
 *   <li>我们的回调地址抖动、发版重启、网络分区，都会让通知打不到。</li>
 *   <li>通道重试次数有限（微信 15 次、间隔递增至 6 小时），耗尽后不再推送。</li>
 *   <li>被标记为 spam 的回调域名、证书过期、防火墙策略变更，都是静默失败。</li>
 * </ul>
 * 如果只依赖通知，「用户已付款、订单仍显示待支付」就是必然结果，而不是偶发故障。
 *
 * <p>因此架构上必须有两条腿走路：<b>通知为主（实时），查单兜底（可靠）</b>。
 * 补偿任务持续扫描处于 {@code isPending()} 且超过阈值时间的订单，主动查单推进状态。
 *
 * <p>查询有两个维度，适配器按通道支持情况选择：
 * 平台订单号（我们生成的）或商户订单号 + 通道交易号。
 */
public interface ChannelQueryPort extends ChannelPort {

    ChannelResult query(ChannelQueryRequest request);

    record ChannelQueryRequest(
            ChannelCode channel,
            PaymentOrderId orderId,
            PaymentAttemptId attemptId,

            /** 平台侧商户订单号。国内通道以此查询。 */
            String merchantOrderNo,

            /** 通道侧交易号。已拿到过则优先用它，查询最精确。 */
            String channelTransactionId
    ) {

        public static ChannelQueryRequest byMerchantOrderNo(ChannelCode channel,
                                                            PaymentOrderId orderId,
                                                            PaymentAttemptId attemptId,
                                                            String merchantOrderNo) {
            return new ChannelQueryRequest(channel, orderId, attemptId, merchantOrderNo, null);
        }

        public static ChannelQueryRequest byTransactionId(ChannelCode channel,
                                                          PaymentOrderId orderId,
                                                          PaymentAttemptId attemptId,
                                                          String channelTransactionId) {
            return new ChannelQueryRequest(channel, orderId, attemptId, null, channelTransactionId);
        }
    }
}
