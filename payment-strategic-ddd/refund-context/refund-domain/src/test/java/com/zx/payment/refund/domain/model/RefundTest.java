package com.zx.payment.refund.domain.model;

import com.zx.payment.shared.ChannelCode;
import com.zx.payment.shared.CurrencyCode;
import com.zx.payment.shared.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class RefundTest {

    private static final Money PAID_100 = Money.ofMinor(1000, CurrencyCode.CNY); // 已付 ¥10.00

    private PaidFact paidFact() {
        return new PaidFact("PAY-001", "M001", "ORDER-001", PAID_100,
                ChannelCode.WECHATPAY, "WX-TN-001", Instant.now());
    }

    @Test
    @DisplayName("退款基于支付事实判断可退性，不依赖收单上下文的聚合")
    void 基于支付事实计算可退余额() {
        Money alreadyRefunded = Money.ofMinor(400, CurrencyCode.CNY); // 已退 ¥4.00

        Refund r = Refund.apply("RF-001", paidFact(),
                Money.ofMinor(600, CurrencyCode.CNY), alreadyRefunded, "部分退款");

        assertEquals(RefundStatus.PROCESSING, r.status());
        assertEquals("PAY-001", r.paymentId());
        assertEquals(Money.ofMinor(600, CurrencyCode.CNY), r.amount());
    }

    @Test
    @DisplayName("超额退款拦截：可退 ¥6 却要退 ¥6.01，直接抛异常")
    void 超额退款拦截() {
        Money alreadyRefunded = Money.ofMinor(400, CurrencyCode.CNY);

        assertThrows(IllegalArgumentException.class, () ->
                Refund.apply("RF-002", paidFact(),
                        Money.ofMinor(601, CurrencyCode.CNY), alreadyRefunded, "退多了"));
    }

    @Test
    @DisplayName("全额退完后再退一分钱，必须被拦住")
    void 已全额退款不可再退() {
        Money fullyRefunded = PAID_100;

        assertThrows(IllegalArgumentException.class, () ->
                Refund.apply("RF-003", paidFact(),
                        Money.ofMinor(1, CurrencyCode.CNY), fullyRefunded, "还想退"));
    }

    @Test
    @DisplayName("币种不一致拒绝退款")
    void 币种校验() {
        assertThrows(IllegalArgumentException.class, () ->
                Refund.apply("RF-004", paidFact(),
                        Money.ofMinor(100, CurrencyCode.USD),
                        Money.zero(CurrencyCode.CNY), "币种错了"));
    }

    @Test
    @DisplayName("幂等：重复确认退款成功返回 false，不重复推进")
    void 退款成功幂等() {
        Refund r = Refund.apply("RF-005", paidFact(),
                Money.ofMinor(100, CurrencyCode.CNY), Money.zero(CurrencyCode.CNY), "测试");

        assertTrue(r.succeed(Instant.now()));
        assertFalse(r.succeed(Instant.now()), "第二次确认应幂等返回 false");
        assertEquals(RefundStatus.SUCCEEDED, r.status());
    }

    @Test
    @DisplayName("终态不可逆：已成功后不能改成失败")
    void 终态不可逆() {
        Refund r = Refund.apply("RF-006", paidFact(),
                Money.ofMinor(100, CurrencyCode.CNY), Money.zero(CurrencyCode.CNY), "测试");

        r.succeed(Instant.now());
        assertFalse(r.fail("X", "想改状态"), "已成功不可再置为失败");
        assertEquals(RefundStatus.SUCCEEDED, r.status());
    }

    @Test
    @DisplayName("退款单号是幂等键，为空时拒绝")
    void 退款单号必填() {
        assertThrows(IllegalArgumentException.class, () ->
                Refund.apply("", paidFact(),
                        Money.ofMinor(100, CurrencyCode.CNY), Money.zero(CurrencyCode.CNY), "测试"));
    }
}
