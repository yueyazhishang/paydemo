package com.demo.payment.domain.channel.model;

/**
 * 通道返回的业务结果状态。
 *
 * <p><b>为什么必须有 UNKNOWN 这一态？</b>
 * 这是支付系统一致性设计的<b>分水岭</b>。
 *
 * <p>调用通道时网络超时，你不知道请求到底有没有被通道处理。
 * 此时如果武断地判定为"失败"并关闭订单，实际通道可能已经扣款成功 ——
 * 用户付了钱，商户没收到单，这就是<b>掉单</b>。
 * 反过来如果判定为"成功"，用户实际没付款，商户就发货了 —— 这是<b>资损</b>。
 *
 * <p>正确做法：超时一律返回 {@code UNKNOWN}，订单保持"支付中"，
 * 然后<b>以主动查证为准</b>定终态。任何把网络超时直接映射成"失败"的代码，
 * 都是一个潜在的掉单 bug。
 */
public enum ChannelResultStatus {

    /** 已受理，等待用户完成支付（微信拿到 prepay_id、Stripe 拿到 client_secret） */
    PENDING,

    /** 通道明确返回成功 */
    SUCCEEDED,

    /** 通道明确返回失败（余额不足、风控拦截、卡被拒等） */
    FAILED,

    /**
     * 结果未知 —— 网络超时、响应无法解析、通道返回 5xx。
     * <b>必须通过主动查证确认，绝不能当作失败处理。</b>
     */
    UNKNOWN,

    /** 已授权但未请款（两段式通道特有） */
    AUTHORIZED,
    ;

    public boolean isFinal() {
        return this == SUCCEEDED || this == FAILED || this == AUTHORIZED;
    }
}
