package com.payment.domain.payment.model.valueobject;

import lombok.Value;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;

/**
 * 金额值对象
 * 
 * 使用BigDecimal保证精度，封装货币类型
 * 不可变对象，确保线程安全
 */
@Value
public class Money {
    
    BigDecimal amount;
    Currency currency;
    
    public static final Currency CNY = Currency.getInstance("CNY");
    public static final Currency USD = Currency.getInstance("USD");
    public static final Currency EUR = Currency.getInstance("EUR");
    public static final Currency GBP = Currency.getInstance("GBP");
    public static final Currency JPY = Currency.getInstance("JPY");
    
    /**
     * 创建人民币金额
     */
    public static Money ofCny(double amount) {
        return new Money(BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP), CNY);
    }
    
    /**
     * 创建指定货币金额
     */
    public static Money of(double amount, Currency currency) {
        return new Money(BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP), currency);
    }
    
    /**
     * 创建指定货币金额(字符串)
     */
    public static Money of(String amount, String currencyCode) {
        return new Money(new BigDecimal(amount).setScale(2, RoundingMode.HALF_UP), Currency.getInstance(currencyCode));
    }
    
    /**
     * 加法运算
     */
    public Money add(Money other) {
        validateSameCurrency(other);
        return new Money(this.amount.add(other.amount), this.currency);
    }
    
    /**
     * 减法运算
     */
    public Money subtract(Money other) {
        validateSameCurrency(other);
        return new Money(this.amount.subtract(other.amount), this.currency);
    }
    
    /**
     * 乘法运算
     */
    public Money multiply(int multiplier) {
        return new Money(this.amount.multiply(BigDecimal.valueOf(multiplier)), this.currency);
    }
    
    /**
     * 判断是否大于
     */
    public boolean isGreaterThan(Money other) {
        validateSameCurrency(other);
        return this.amount.compareTo(other.amount) > 0;
    }
    
    /**
     * 判断是否为零
     */
    public boolean isZero() {
        return this.amount.compareTo(BigDecimal.ZERO) == 0;
    }
    
    /**
     * 判断是否为正数
     */
    public boolean isPositive() {
        return this.amount.compareTo(BigDecimal.ZERO) > 0;
    }
    
    /**
     * 获取最小值
     */
    public Money min(Money other) {
        validateSameCurrency(other);
        return new Money(this.amount.min(other.amount), this.currency);
    }
    
    private void validateSameCurrency(Money other) {
        if (!this.currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                String.format("货币类型不匹配: %s vs %s", this.currency, other.currency));
        }
    }
    
    /**
     * 转换为分的整数(用于微信支付等需要分的场景)
     */
    public long toCents() {
        return this.amount.multiply(BigDecimal.valueOf(100)).longValue();
    }
    
    @Override
    public String toString() {
        return String.format("%s %s", currency.getCurrencyCode(), amount.toPlainString());
    }
}
