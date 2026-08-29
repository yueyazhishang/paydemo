package com.zx.payment.shared;

import java.util.Objects;

/**
 * 值对象：金额 + 币种。共享内核的核心成员。
 *
 * 为什么必须存在：支付系统两类经典事故——币种错配（CNY 减 USD）与浮点误差（0.1+0.2≠0.3）。
 * 把这两条不变量封进值对象，比散落各处的 if 判断可靠得多。
 *
 * 设计约束：
 *  1. 不可变（final 字段、无 setter）；
 *  2. 内部一律用【最小货币单位】long 存储（分 / cent），杜绝浮点；
 *  3. 任何跨币种运算直接抛异常，不做隐式换算——换算需要汇率与生效时间，是领域概念不是 Money 的职责。
 */
public final class Money {

    private final long amountMinor;
    private final CurrencyCode currency;

    private Money(long amountMinor, CurrencyCode currency) {
        this.amountMinor = amountMinor;
        this.currency = Objects.requireNonNull(currency, "币种不能为空");
    }

    public static Money ofMinor(long amountMinor, CurrencyCode currency) {
        return new Money(amountMinor, currency);
    }

    /** 从主单位构造（如 1.23 元）。仅用于外部输入边界，内部一律走 ofMinor。 */
    public static Money ofMajor(java.math.BigDecimal major, CurrencyCode currency) {
        Objects.requireNonNull(major, "金额不能为空");
        long minor = major.movePointRight(currency.scale()).longValueExact();
        return new Money(minor, currency);
    }

    public static Money zero(CurrencyCode currency) {
        return new Money(0, currency);
    }

    public Money add(Money other) {
        requireSameCurrency(other);
        return new Money(this.amountMinor + other.amountMinor, this.currency);
    }

    public Money subtract(Money other) {
        requireSameCurrency(other);
        return new Money(this.amountMinor - other.amountMinor, this.currency);
    }

    /** 乘以整数倍（用于计算手续费、分账比例的整数化表达）。 */
    public Money multiply(long factor) {
        return new Money(this.amountMinor * factor, this.currency);
    }

    public boolean isGreaterThan(Money other) {
        requireSameCurrency(other);
        return this.amountMinor > other.amountMinor;
    }

    public boolean isGreaterThanOrEqual(Money other) {
        requireSameCurrency(other);
        return this.amountMinor >= other.amountMinor;
    }

    public boolean isPositive() {
        return amountMinor > 0;
    }

    public boolean isZero() {
        return amountMinor == 0;
    }

    private void requireSameCurrency(Money other) {
        if (!this.currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    String.format("币种不一致，无法运算：%s vs %s", this.currency, other.currency));
        }
    }

    public long amountMinor() {
        return amountMinor;
    }

    public CurrencyCode currency() {
        return currency;
    }

    /** 主单位表示，仅供展示与外部交互。 */
    public java.math.BigDecimal toMajor() {
        return java.math.BigDecimal.valueOf(amountMinor).movePointLeft(currency.scale());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Money other)) return false;
        return amountMinor == other.amountMinor && currency == other.currency;
    }

    @Override
    public int hashCode() {
        return Objects.hash(amountMinor, currency);
    }

    @Override
    public String toString() {
        return toMajor().toPlainString() + " " + currency.code();
    }
}
