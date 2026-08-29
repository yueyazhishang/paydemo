package com.demo.payment.domain.channel.spi;

import com.demo.payment.domain.channel.model.ChannelResultStatus;
import com.demo.payment.shared.money.Money;

/**
 * 退款响应。
 *
 * <p><b>关于退款的同步/异步差异：</b>
 * <ul>
 *   <li>微信/支付宝：退款请求<b>同步返回受理结果</b>，实际到账异步通过 refunds 回调通知。
 *       但注意 —— 同步返回 SUCCESS 只代表"通道受理了"，不代表钱已退到用户账上。</li>
 *   <li>Stripe：同步返回 refund 对象，状态可立即确定。</li>
 *   <li>PayPal：退款同步完成，但资金到账可能有延迟。</li>
 * </ul>
 *
 * <p>因此退款单同样需要"退款中"状态 + 查证补偿，不能同步返回成功就置终态。
 */
public record RefundResponse(
        String outRefundNo,
        ChannelResultStatus status,
        String channelRefundId,
        Money refundedAmount,
        String code,
        String message,
        boolean infrastructureError
) {
    public static RefundResponse succeeded(String outRefundNo, String channelRefundId, Money amount) {
        return new RefundResponse(outRefundNo, ChannelResultStatus.SUCCEEDED,
                channelRefundId, amount, null, null, false);
    }

    public static RefundResponse failed(String outRefundNo, String code, String message) {
        return new RefundResponse(outRefundNo, ChannelResultStatus.FAILED, null,
                null, code, message, false);
    }

    public static RefundResponse unknown(String outRefundNo, String message) {
        return new RefundResponse(outRefundNo, ChannelResultStatus.UNKNOWN, null,
                null, "UNKNOWN", message, true);
    }
}
