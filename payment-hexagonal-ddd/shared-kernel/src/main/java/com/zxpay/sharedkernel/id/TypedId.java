package com.zxpay.sharedkernel.id;

import java.util.Objects;
import java.util.UUID;

/**
 * 类型化标识基类。
 *
 * <p>直接用 {@code String paymentOrderId} 的代价：方法签名上无法区分「商户订单号」和
 * 「支付单号」，一旦传反，编译期毫无察觉，只能等线上资金出错。类型化 ID 把这类
 * 错误前移到编译期，是支付系统性价比最高的防御手段之一。
 *
 * <p>子类只需继承并暴露工厂方法：
 * <pre>{@code
 * public final class PaymentOrderId extends TypedId {
 *     public PaymentOrderId(String value) { super(value); }
 *     public static PaymentOrderId generate() { return new PaymentOrderId("PAY" + ...); }
 * }
 * }</pre>
 *
 * <p>equals 用 {@code getClass()} 比较，确保 {@code PaymentOrderId("X")}
 * 与 {@code RefundOrderId("X")} 不相等——即使值相同。
 */
public abstract class TypedId {

    private static final int MAX_LENGTH = 64;

    private final String value;

    protected TypedId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(getClass().getSimpleName() + " must not be blank");
        }
        String trimmed = value.trim();
        if (trimmed.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    getClass().getSimpleName() + " exceeds max length " + MAX_LENGTH + ": " + trimmed);
        }
        this.value = trimmed;
    }

    public String value() {
        return value;
    }

    /** 生成带业务前缀的标识，便于日志排查与人工识别。 */
    protected static String generate(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TypedId typedId = (TypedId) o;
        return value.equals(typedId.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(getClass(), value);
    }

    @Override
    public String toString() {
        return value;
    }
}
