package com.demo.payment.domain.channel.spi;

import com.demo.payment.domain.acquiring.model.vo.OutTradeNo;
import com.demo.payment.domain.channel.model.ChannelResultStatus;
import com.demo.payment.shared.money.Money;

import java.time.Instant;

/**
 * 回调解析结果 —— 归一化后的通知。
 *
 * <p>各通道的通知形态差异极大：
 * <pre>
 *   微信 v3   → JSON body + Wechatpay-Signature 头 + 平台证书（需解密 resource 字段）
 *   支付宝    → form-urlencoded + sign 参数（RSA2）
 *   Stripe    → JSON body + Stripe-Signature 头（HMAC-SHA256 + 时间戳防重放）
 *   PayPal    → JSON body + 需二次调用 verify-webhook 验签（PayPal 不提供本地验签）
 *   Worldpay  → <b>XML</b> 通知 + MAC 校验
 *   Antom     → JSON + HMAC-SHA256 签名头
 * </pre>
 *
 * <p>适配层的职责就是把上述所有形态统一成这个结构，上层再也见不到 XML 和 form 编码。
 */
public record NotificationParseResult(
        OutTradeNo outTradeNo,
        String channelTransactionId,
        ChannelResultStatus status,
        String channelRawStatus,
        Money amount,

        /**
         * 通道侧的通知唯一 ID。
         * 用于<b>通知去重</b>：同一笔交易通道可能重投多次（网络重试、补偿推送），
         * 必须按 notifyId 去重，否则会重复触发业务逻辑。
         */
        String notifyId,

        /** 通知类型：payment / refund / dispute（拒付） */
        String notifyType,

        /** 通道侧事件发生时间 */
        Instant occurredAt,

        /** 原始报文，保留以便问题追溯与重放 */
        String rawBody
) {
    public boolean isPaymentNotify() { return "payment".equals(notifyType); }
    public boolean isRefundNotify() { return "refund".equals(notifyType); }
    public boolean isDisputeNotify() { return "dispute".equals(notifyType); }

    /**
     * 是否已有明确的终态结论。
     * 若通道只通知"支付中"，则不更新订单状态，只记日志。
     */
    public boolean hasFinalResult() { return status != null && status.isFinal(); }
}
