package com.example.payment.domain.shared;

import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 金额值对象（不可变）。统一以「最小货币单位」(long) 存储，
 * 屏蔽各渠道「分(int) / 元(字符串) / 币种最小单位」的差异。
 */
@Getter
@EqualsAndHashCode
public class Money {

    private final long amountMinor;
    private final Currency currency;

    private Money(long amountMinor, Currency currency) {
        if (currency == null) {
            throw new IllegalArgumentException("币种不能为空");
        }
        if (amountMinor < 0) {
            throw new IllegalArgumentException("金额不能为负: " + amountMinor);
        }
        this.amountMinor = amountMinor;
        this.currency = currency;
    }

    public static Money ofMinor(long amountMinor, Currency currency) {
        return new Money(amountMinor, currency);
    }

    /** 由主单位金额（如 99.99 元）构造，按币种 scale 归一化为最小单位 */
    public static Money ofMajor(BigDecimal majorAmount, Currency currency) {
        BigDecimal minor = majorAmount.setScale(currency.getScale(), RoundingMode.UNNECESSARY)
                .movePointRight(currency.getScale());
        return new Money(minor.longValueExact(), currency);
    }

    public boolean isPositive() {
        return amountMinor > 0;
    }

    public Money add(Money other) {
        checkSameCurrency(other);
        return new Money(this.amountMinor + other.amountMinor, currency);
    }

    public Money subtract(Money other) {
        checkSameCurrency(other);
        return new Money(this.amountMinor - other.amountMinor, currency);
    }

    public boolean isGreaterThanOrEqual(Money other) {
        checkSameCurrency(other);
        return this.amountMinor >= other.amountMinor;
    }

    private void checkSameCurrency(Money other) {
        if (this.currency != other.currency) {
            throw new IllegalArgumentException("币种不一致: " + this.currency + " vs " + other.currency);
        }
    }

    @Override
    public String toString() {
        return BigDecimal.valueOf(amountMinor, currency.getScale()).toPlainString()
                + " " + currency.name();
    }
}
