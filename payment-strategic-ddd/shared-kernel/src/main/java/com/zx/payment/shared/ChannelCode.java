package com.zx.payment.shared;

/**
 * 值对象：通道标识。
 *
 * 为什么放在共享内核而不是收单上下文：集成事件（PaymentSucceededV1）里要带通道信息，
 * 退款/对账上下文都要读。这是跨上下文通信必需的公共词汇，且它是纯标识、无行为——
 * 符合共享内核"极小、只读、无领域行为"的准入标准。
 *
 * 注意：通道的【能力】（是否支持部分退款、是否有对账单、签名算法）不在这里，
 * 那属于通道网关上下文的领域知识。这里只有一个标识。
 */
public enum ChannelCode {

    WECHATPAY("wechatpay", "微信支付"),
    STRIPE("stripe", "Stripe");

    private final String code;
    private final String displayName;

    ChannelCode(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public String code() {
        return code;
    }

    public String displayName() {
        return displayName;
    }

    public static ChannelCode of(String code) {
        String normalized = code == null ? "" : code.trim().toLowerCase();
        for (ChannelCode c : values()) {
            if (c.code.equals(normalized) || c.name().equalsIgnoreCase(normalized)) {
                return c;
            }
        }
        throw new IllegalArgumentException("不支持的支付通道: " + code);
    }
}
