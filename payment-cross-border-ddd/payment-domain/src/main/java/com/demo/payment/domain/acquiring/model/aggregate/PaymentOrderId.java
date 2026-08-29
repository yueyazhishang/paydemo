package com.demo.payment.domain.acquiring.model.aggregate;

import com.demo.payment.shared.util.IdGenerator;

import java.util.Objects;

/**
 * 支付单 ID 值对象。
 *
 * <p><b>为什么不用裸 String？</b>
 * 支付系统里 ID 满天飞：paymentOrderId / merchantOrderNo / outTradeNo /
 * channelTransactionId / refundNo。如果全用 String，方法签名
 * {@code void refund(String a, String b)} 传错参数顺序，编译器不会报错，
 * 上线就把 A 商户的钱退给了 B 订单。<b>用类型包装是这个 bug 的唯一根治手段。</b>
 */
public final class PaymentOrderId {

    private final String value;

    private PaymentOrderId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("paymentOrderId must not be blank");
        }
        this.value = value;
    }

    public static PaymentOrderId newId() { return new PaymentOrderId(IdGenerator.paymentOrderId()); }
    public static PaymentOrderId of(String value) { return new PaymentOrderId(value); }

    public String value() { return value; }

    @Override
    public boolean equals(Object o) {
        if (this == o) { return true; }
        if (!(o instanceof PaymentOrderId other)) { return false; }
        return value.equals(other.value);
    }

    @Override
    public int hashCode() { return value.hashCode(); }

    @Override
    public String toString() { return value; }
}
