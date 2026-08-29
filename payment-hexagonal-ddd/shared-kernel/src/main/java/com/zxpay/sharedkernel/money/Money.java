package com.zxpay.sharedkernel.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 金额值对象。
 *
 * <p>设计要点：
 * <ol>
 *   <li><b>不可变</b>。所有运算返回新实例，杜绝金额被就地修改。</li>
 *   <li><b>永不用 double 构造</b>。只接受 String / BigDecimal / 最小单位 long，
 *       避免 0.1 这类二进制浮点误差进入资金链路。</li>
 *   <li><b>精度由币种决定</b>。构造时统一 {@code setScale(currency.minorUnits())}，
 *       保证同币种的两个 Money 在 equals 上稳定可比（1.0 与 1.00 视为相等）。</li>
 *   <li><b>跨币种运算直接抛异常</b>。人民币加美元是业务错误，不是技术问题，
 *       必须在值对象层面就拦住，而不是留给下游对账发现。</li>
 * </ol>
 *
 * <p>{@code record} 自动生成的 equals/hashCode 基于归一化后的 BigDecimal 与币种，
 * 正好满足值对象语义。
 */
public final class Money implements Comparable<Money> {

    public static final Money ZERO_CNY = Money.of("0", Currency.CNY);

    private final BigDecimal amount;
    private final Currency currency;

    private Money(BigDecimal amount, Currency currency) {
        this.amount = Objects.requireNonNull(amount, "amount")
                .setScale(currency.minorUnits(), RoundingMode.UNNECESSARY);
        this.currency = Objects.requireNonNull(currency, "currency");
    }

    // ---------- 工厂方法 ----------

    /** 从十进制字符串构造，推荐用于解析外部入参。 */
    public static Money of(String amount, Currency currency) {
        Objects.requireNonNull(currency, "currency");
        if (amount == null || amount.isBlank()) {
            throw new IllegalArgumentException("amount must not be blank");
        }
        return new Money(new BigDecimal(amount.trim()), currency);
    }

    public static Money of(BigDecimal amount, Currency currency) {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        return new Money(amount, currency);
    }

    /**
     * 从最小单位（分、日元的最小单位即 1 元）构造。通道报文与数据库存储都推荐走这个入口。
     *
     * <p>注意：JPY 的 minorUnits=0，因此 {@code ofMinor(100, JPY)} 表示 100 日元，
     * 而 {@code ofMinor(100, USD)} 表示 1.00 美元。差异全部由币种自己承担。
     */
    public static Money ofMinor(long minorUnits, Currency currency) {
        Objects.requireNonNull(currency, "currency");
        return new Money(BigDecimal.valueOf(minorUnits, currency.minorUnits()), currency);
    }

    public static Money zero(Currency currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    // ---------- 读取 ----------

    public BigDecimal amount() {
        return amount;
    }

    public Currency currency() {
        return currency;
    }

    /** 最小单位金额：通道报文、DB 存储统一用 long，避免精度丢失。 */
    public long minorUnits() {
        return amount.movePointRight(currency.minorUnits()).longValueExact();
    }

    // ---------- 运算 ----------

    public Money plus(Money other) {
        assertSameCurrency(other);
        return new Money(this.amount.add(other.amount), currency);
    }

    public Money minus(Money other) {
        assertSameCurrency(other);
        return new Money(this.amount.subtract(other.amount), currency);
    }

    /** 按比率取金额，例如手续费 = 金额 * 费率。结果按币种精度向下取整到最小单位。 */
    public Money multiply(BigDecimal factor) {
        Objects.requireNonNull(factor, "factor");
        return new Money(amount.multiply(factor).setScale(currency.minorUnits(), RoundingMode.DOWN), currency);
    }

    /**
     * 把金额拆成 n 份，余数逐个摊到前几份。
     *
     * <p>典型场景：多次部分退款时校验拆分后总额守恒；或优惠券按订单行分摊。
     * 例如 {@code 10.00 CNY.allocate(3) -> [3.34, 3.33, 3.33]}，累加后必须等于原额。
     */
    public List<Money> allocate(int parts) {
        if (parts <= 0) {
            throw new IllegalArgumentException("parts must be positive: " + parts);
        }
        long total = minorUnits();
        long base = total / parts;
        long remainder = total % parts;

        List<Money> result = new ArrayList<>(parts);
        for (int i = 0; i < parts; i++) {
            result.add(Money.ofMinor(base + (i < remainder ? 1 : 0), currency));
        }
        return List.copyOf(result);
    }

    // ---------- 判断 ----------

    public boolean isZero() {
        return amount.signum() == 0;
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    /** 是否超过了 other（跨币种抛异常）。 */
    public boolean isGreaterThan(Money other) {
        return this.compareTo(other) > 0;
    }

    public boolean isGreaterThanOrEqual(Money other) {
        return this.compareTo(other) >= 0;
    }

    public boolean isLessThan(Money other) {
        return this.compareTo(other) < 0;
    }

    private void assertSameCurrency(Money other) {
        Objects.requireNonNull(other, "other");
        if (this.currency != other.currency) {
            throw new IllegalArgumentException(
                    "currency mismatch: cannot operate " + this.currency.code() + " with " + other.currency.code());
        }
    }

    @Override
    public int compareTo(Money other) {
        assertSameCurrency(other);
        return this.amount.compareTo(other.amount);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Money money)) {
            return false;
        }
        // 用 compareTo 而非 amount.equals，保证 1.0 与 1.00 相等
        return currency == money.currency && amount.compareTo(money.amount) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(amount.stripTrailingZeros(), currency);
    }

    @Override
    public String toString() {
        return amount.toPlainString() + " " + currency.code();
    }
}
