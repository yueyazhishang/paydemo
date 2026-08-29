package com.zx.payment.acquisition.domain.model;

import com.zx.payment.acquisition.domain.event.PaymentClosedEvent;
import com.zx.payment.acquisition.domain.event.PaymentSucceededEvent;
import com.zx.payment.shared.ChannelCode;
import com.zx.payment.shared.CurrencyCode;
import com.zx.payment.shared.DomainEvent;
import com.zx.payment.shared.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 支付聚合的行为测试。
 *
 * 这些用例不是凑覆盖率——每一条都对应一条业务不变量，
 * 测试即文档：看这个类的用例列表，就知道这个聚合守护了什么。
 */
class PaymentTest {

    private static final Money AMOUNT_100 = Money.ofMinor(1000, CurrencyCode.CNY); // ¥10.00

    private Payment newPayment(Money amount) {
        return Payment.create("M001", "ORDER-001", "测试订单", amount,
                Instant.now().plus(30, ChronoUnit.MINUTES));
    }

    // ==================== 正常流转 ====================

    @Test
    @DisplayName("全额支付成功：CREATED → PAYING → SUCCESS")
    void 全额支付成功() {
        Payment p = newPayment(AMOUNT_100);
        assertEquals(PaymentStatus.CREATED, p.status());

        PaymentAttempt a = p.startAttempt(ChannelCode.WECHATPAY, AMOUNT_100);
        assertEquals(PaymentStatus.PAYING, p.status());
        assertEquals(AttemptStatus.INITIATED, a.status());
        assertEquals(1, a.attemptNo());

        assertTrue(p.confirmAttemptSuccess(a.attemptNo(), AMOUNT_100, Instant.now()));
        assertEquals(PaymentStatus.SUCCESS, p.status());
        assertEquals(AMOUNT_100, p.receivedAmount());
        assertTrue(p.outstandingAmount().isZero());
    }

    @Test
    @DisplayName("下单后推进到 PAYING 才允许确认成功，状态机拒绝跳跃")
    void 状态机拒绝非法迁移() {
        Payment p = newPayment(AMOUNT_100);
        PaymentAttempt a = p.startAttempt(ChannelCode.WECHATPAY, AMOUNT_100);

        // 尝试确认一个不存在的 attempt
        assertThrows(IllegalArgumentException.class,
                () -> p.confirmAttemptSuccess(99, AMOUNT_100, Instant.now()));

        // 已成功后关单，应被终态拦住
        p.confirmAttemptSuccess(a.attemptNo(), AMOUNT_100, Instant.now());
        assertFalse(p.close("商户取消"), "SUCCESS 是终态，不可关单");
        assertEquals(PaymentStatus.SUCCESS, p.status());
    }

    // ==================== 部分支付 ====================

    @Test
    @DisplayName("部分支付：先收 ¥4 → PARTIAL，补齐 ¥6 → SUCCESS")
    void 部分支付后补齐() {
        Payment p = newPayment(AMOUNT_100);

        PaymentAttempt a1 = p.startAttempt(ChannelCode.WECHATPAY, Money.ofMinor(400, CurrencyCode.CNY));
        p.confirmAttemptSuccess(a1.attemptNo(), Money.ofMinor(400, CurrencyCode.CNY), Instant.now());
        assertEquals(PaymentStatus.PARTIAL, p.status());
        assertEquals(Money.ofMinor(600, CurrencyCode.CNY), p.outstandingAmount());

        PaymentAttempt a2 = p.startAttempt(ChannelCode.STRIPE, Money.ofMinor(600, CurrencyCode.CNY));
        p.confirmAttemptSuccess(a2.attemptNo(), Money.ofMinor(600, CurrencyCode.CNY), Instant.now());
        assertEquals(PaymentStatus.SUCCESS, p.status());
        assertEquals(AMOUNT_100, p.receivedAmount());
    }

    @Test
    @DisplayName("超收拦截：尝试金额超过待收金额直接抛异常")
    void 超收拦截() {
        Payment p = newPayment(AMOUNT_100);
        assertThrows(IllegalArgumentException.class,
                () -> p.startAttempt(ChannelCode.WECHATPAY, Money.ofMinor(1001, CurrencyCode.CNY)),
                "请求金额超过应付金额，必须拒绝");
    }

    @Test
    @DisplayName("部分支付后关单：已收金额保留在关闭事件中，供下游自动退款")
    void 部分支付后关单需退款() {
        Payment p = newPayment(AMOUNT_100);
        PaymentAttempt a = p.startAttempt(ChannelCode.WECHATPAY, Money.ofMinor(400, CurrencyCode.CNY));
        p.confirmAttemptSuccess(a.attemptNo(), Money.ofMinor(400, CurrencyCode.CNY), Instant.now());

        assertTrue(p.close("超时未付清"));

        PaymentClosedEvent event = p.drainEvents().stream()
                .filter(e -> e instanceof PaymentClosedEvent)
                .map(e -> (PaymentClosedEvent) e)
                .findFirst().orElseThrow();
        assertEquals(Money.ofMinor(400, CurrencyCode.CNY), event.receivedAmount(),
                "关闭事件必须带上已收金额，否则下游不知道要退多少钱");
    }

    // ==================== 多通道重试 ====================

    @Test
    @DisplayName("换通道重试：微信失败 → 切 Stripe 成功")
    void 失败后换通道重试() {
        Payment p = newPayment(AMOUNT_100);

        PaymentAttempt a1 = p.startAttempt(ChannelCode.WECHATPAY, AMOUNT_100);
        p.confirmAttemptFailure(a1.attemptNo(), "BALANCE_NOT_ENOUGH", "余额不足", true);
        assertEquals(PaymentStatus.FAILED, p.status());
        assertFalse(p.status().isFinal(), "FAILED 可重试，不是终态");

        PaymentAttempt a2 = p.startAttempt(ChannelCode.STRIPE, AMOUNT_100);
        assertEquals(2, a2.attemptNo());
        p.confirmAttemptSuccess(a2.attemptNo(), AMOUNT_100, Instant.now());
        assertEquals(PaymentStatus.SUCCESS, p.status());
    }

    @Test
    @DisplayName("尝试次数上限：达到 MAX_ATTEMPTS 后拒绝再发起")
    void 尝试次数上限() {
        Payment p = newPayment(AMOUNT_100);
        for (int i = 1; i <= Payment.MAX_ATTEMPTS; i++) {
            PaymentAttempt a = p.startAttempt(ChannelCode.WECHATPAY, AMOUNT_100);
            p.confirmAttemptFailure(a.attemptNo(), "FAIL", "失败", true);
        }
        assertThrows(IllegalStateException.class,
                () -> p.startAttempt(ChannelCode.STRIPE, AMOUNT_100),
                "超过最大尝试次数必须拒绝，防止无限重试打爆通道");
    }

    @Test
    @DisplayName("并发下单拦截：已有进行中的尝试时不允许再发起")
    void 并发下单拦截() {
        Payment p = newPayment(AMOUNT_100);
        p.startAttempt(ChannelCode.WECHATPAY, AMOUNT_100);

        assertThrows(IllegalStateException.class,
                () -> p.startAttempt(ChannelCode.STRIPE, AMOUNT_100),
                "同一时刻只能有一个 active attempt，防止重复扣款");
    }

    @Test
    @DisplayName("不可重试的失败：直接判定最终失败并发出事件")
    void 不可重试的失败() {
        Payment p = newPayment(AMOUNT_100);
        PaymentAttempt a = p.startAttempt(ChannelCode.WECHATPAY, AMOUNT_100);

        p.confirmAttemptFailure(a.attemptNo(), "RISK_REJECT", "风控拒绝", false);

        List<DomainEvent> events = p.drainEvents();
        assertTrue(events.stream().anyMatch(e -> e.getClass().getSimpleName()
                .equals("PaymentFailedEvent")));
    }

    // ==================== 幂等 ====================

    @Test
    @DisplayName("幂等：通道重复回调成功通知，第二次返回 false 且不重复发事件")
    void 重复成功回调幂等() {
        Payment p = newPayment(AMOUNT_100);
        p.drainEvents(); // 清掉创建事件

        PaymentAttempt a = p.startAttempt(ChannelCode.WECHATPAY, AMOUNT_100);
        assertTrue(p.confirmAttemptSuccess(a.attemptNo(), AMOUNT_100, Instant.now()));
        assertFalse(p.confirmAttemptSuccess(a.attemptNo(), AMOUNT_100, Instant.now()),
                "第二次确认应幂等返回 false");

        long successEvents = p.drainEvents().stream()
                .filter(e -> e instanceof PaymentSucceededEvent).count();
        assertEquals(1, successEvents, "幂等后不能重复发出成功事件");
    }

    @Test
    @DisplayName("幂等：重复关单返回 false，不重复发事件")
    void 重复关单幂等() {
        Payment p = newPayment(AMOUNT_100);
        p.drainEvents();

        assertTrue(p.close("超时"));
        assertFalse(p.close("超时"), "重复关单应幂等返回 false");

        long closedEvents = p.drainEvents().stream()
                .filter(e -> e instanceof PaymentClosedEvent).count();
        assertEquals(1, closedEvents);
    }

    // ==================== 并发控制（乐观锁）====================

    @Test
    @DisplayName("乐观锁：每次状态变更 version 递增，供仓储 CAS 使用")
    void 版本号递增() {
        Payment p = newPayment(AMOUNT_100);
        assertEquals(1, p.version());

        PaymentAttempt a = p.startAttempt(ChannelCode.WECHATPAY, AMOUNT_100);
        assertEquals(2, p.version(), "发起尝试应递增版本");

        p.confirmAttemptSuccess(a.attemptNo(), AMOUNT_100, Instant.now());
        assertEquals(3, p.version(), "确认成功应递增版本");
    }

    // ==================== 超时 ====================

    @Test
    @DisplayName("超时判定：未终态且已过 expireTime 才算过期")
    void 超时判定() {
        Payment p = Payment.create("M001", "ORDER-002", "测试", AMOUNT_100,
                Instant.now().plus(1, ChronoUnit.MINUTES));

        assertFalse(p.isExpired(Instant.now()), "未到过期时间");
        assertTrue(p.isExpired(Instant.now().plus(2, ChronoUnit.MINUTES)), "已过过期时间");

        p.startAttempt(ChannelCode.WECHATPAY, AMOUNT_100);
        p.confirmAttemptSuccess(1, AMOUNT_100, Instant.now());
        assertFalse(p.isExpired(Instant.now().plus(2, ChronoUnit.MINUTES)),
                "终态不再参与超时扫描");
    }

    // ==================== 值对象 ====================

    @Test
    @DisplayName("Money：跨币种运算必须抛异常")
    void 跨币种运算拒绝() {
        Money cny = Money.ofMinor(1000, CurrencyCode.CNY);
        Money usd = Money.ofMinor(1000, CurrencyCode.USD);
        assertThrows(IllegalArgumentException.class, () -> cny.add(usd));
    }

    @Test
    @DisplayName("Money：日元无小数位，100 JPY 应记为 100 而非 1")
    void 日元精度() {
        Money jpy = Money.ofMajor(new java.math.BigDecimal("100"), CurrencyCode.JPY);
        assertEquals(100, jpy.amountMinor());
        assertEquals(0, CurrencyCode.JPY.scale());
    }
}
