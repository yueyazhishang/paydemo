package com.demo.payment.shared;

import com.demo.payment.shared.money.Currency;
import com.demo.payment.shared.money.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Money 值对象测试 —— 这里每个用例都对应一个真实的资金事故类型。
 */
class MoneyTest {

    @Test
    @DisplayName("KWD 是三位小数币种，1.234 KWD 的最小单位是 1234 而不是 123")
    void kwdHasThreeDecimalPlaces() {
        Money m = Money.ofMajor("1.234", Currency.KWD);
        assertEquals(1234L, m.minorUnits());
        assertEquals(0, new BigDecimal("1.234").compareTo(m.majorValue()));
    }

    @Test
    @DisplayName("JPY 是零小数币种，100 日元的最小单位就是 100")
    void jpyHasZeroDecimalPlaces() {
        Money m = Money.ofMajor("100", Currency.JPY);
        assertEquals(100L, m.minorUnits());
        assertTrue(m.currency().isZeroDecimal(), "JPY 应被识别为零小数币种");

        // 给零小数币种传小数，必须快速失败而不是静默截断
        assertThrows(ArithmeticException.class,
                () -> Money.ofMajor("100.5", Currency.JPY));
    }

    @Test
    @DisplayName("跨币种相加必须抛异常，避免 100 JPY + 1 USD = 101 的荒谬结果")
    void crossCurrencyAdditionRejected() {
        Money jpy = Money.ofMajor("100", Currency.JPY);
        Money usd = Money.ofMajor("1", Currency.USD);
        assertThrows(IllegalArgumentException.class, () -> jpy.plus(usd));
    }

    @Test
    @DisplayName("分账时余数必须被摊掉，保证各部分之和严格等于原额")
    void allocateDistributesRemainderExactly() {
        // 100 分按 1:1:1 分，结果应为 34/33/33，而不是 33/33/33（丢 1 分）
        Money total = Money.ofMinor(100, Currency.CNY);
        Money[] parts = total.allocate(1, 1, 1);

        long sum = parts[0].minorUnits() + parts[1].minorUnits() + parts[2].minorUnits();
        assertEquals(100L, sum, "分配后各份之和必须严格等于原额");
        assertEquals(34L, parts[0].minorUnits());
        assertEquals(33L, parts[1].minorUnits());
        assertEquals(33L, parts[2].minorUnits());
    }

    @Test
    @DisplayName("按权重分账：100 分按 7:3 分为 70/30")
    void allocateByWeight() {
        Money total = Money.ofMinor(100, Currency.CNY);
        Money[] parts = total.allocate(7, 3);
        assertEquals(70L, parts[0].minorUnits());
        assertEquals(30L, parts[1].minorUnits());
    }

    @Test
    @DisplayName("Money 不可变：任何运算都返回新对象")
    void moneyIsImmutable() {
        Money original = Money.ofMinor(100, Currency.CNY);
        Money result = original.plus(Money.ofMinor(50, Currency.CNY));
        assertEquals(100L, original.minorUnits(), "原对象不应被修改");
        assertEquals(150L, result.minorUnits());
    }

    @Test
    @DisplayName("金额为负或零的支付必须被拒绝")
    void nonPositiveAmountRejectedForPayment() {
        assertFalse(Money.ofMinor(0, Currency.CNY).isPositive());
        assertFalse(Money.ofMinor(-1, Currency.CNY).isPositive());
    }
}
