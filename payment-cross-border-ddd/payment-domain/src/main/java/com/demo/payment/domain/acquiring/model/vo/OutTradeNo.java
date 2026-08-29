package com.demo.payment.domain.acquiring.model.vo;

import java.util.Objects;

/**
 * 发往通道的订单号值对象。
 *
 * <p><b>这是支付系统里最容易混淆、也最容易出事故的一个概念。</b>
 * 一笔支付至少涉及三层单号，必须严格区分：
 * <pre>
 *   merchantOrderNo      商户系统的订单号（商户自己生成，可能重复投递）
 *   paymentOrderId       本支付平台的订单号（平台生成，全局唯一）
 *   outTradeNo           发往具体通道的订单号（每次通道尝试一个，绝不能复用）
 *   channelTransactionId 通道侧返回的流水号（如微信 transaction_id、Stripe pi_xxx）
 * </pre>
 *
 * <p>混淆 outTradeNo 和 paymentOrderId 是新手最常见的错误：
 * 直接拿 paymentOrderId 去当 outTradeNo，结果一切换通道重试就撞号，
 * 通道返回"订单已存在"，重试逻辑形同虚设。
 */
public final class OutTradeNo {

    /** 微信 out_trade_no 长度上限 */
    public static final int WECHAT_MAX_LENGTH = 32;
    /** 支付宝 out_trade_no 长度上限 */
    public static final int ALIPAY_MAX_LENGTH = 64;

    private final String value;

    private OutTradeNo(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("outTradeNo must not be blank");
        }
        this.value = value;
    }

    public static OutTradeNo of(String value) { return new OutTradeNo(value); }

    public String value() { return value; }

    /** 校验是否满足指定通道的长度约束，避免下单时才被通道打回 */
    public boolean lengthFits(int maxLength) { return value.length() <= maxLength; }

    @Override
    public boolean equals(Object o) {
        if (this == o) { return true; }
        if (!(o instanceof OutTradeNo other)) { return false; }
        return value.equals(other.value);
    }

    @Override
    public int hashCode() { return value.hashCode(); }

    @Override
    public String toString() { return value; }
}
