package com.demo.payment.domain;

import com.demo.payment.domain.acquiring.model.aggregate.PaymentOrder;
import com.demo.payment.domain.acquiring.model.vo.OutTradeNo;
import com.demo.payment.domain.acquiring.statemachine.PaymentStatus;
import com.demo.payment.domain.channel.model.ChannelCode;
import com.demo.payment.domain.channel.model.PaymentMethodType;
import com.demo.payment.shared.money.Currency;
import com.demo.payment.shared.money.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 支付单聚合测试 —— 每个用例都是一类真实的资金安全事故。
 */
class PaymentOrderTest {

    private static final String MERCHANT_ID = "M001";
    private static final String MERCHANT_ORDER_NO = "ORDER_001";
    private static final Money AMOUNT = Money.ofMajor("100.00", Currency.CNY);

    private PaymentOrder order;
    private OutTradeNo outTradeNo;

    @BeforeEach
    void setUp() {
        order = PaymentOrder.create(MERCHANT_ID, MERCHANT_ORDER_NO, AMOUNT,
                PaymentMethodType.WECHAT_PAY, "测试商品", "http://notify",
                Instant.now().plus(30, ChronoUnit.MINUTES));
        outTradeNo = OutTradeNo.of("P202601010000001A1");
        order.startAttempt(ChannelCode.WECHAT_PAY, outTradeNo);
    }

    @Test
    @DisplayName("创建后应处于 PAYING 状态，并产生 PaymentOrderCreated 事件")
    void createProducesEvent() {
        PaymentOrder fresh = PaymentOrder.create(MERCHANT_ID, "ORDER_NEW", AMOUNT,
                PaymentMethodType.ALIPAY_WALLET, "商品", null, null);
        assertEquals(PaymentStatus.CREATED, fresh.status());
        assertFalse(fresh.pullDomainEvents().isEmpty(), "创建应产生领域事件");
    }

    @Test
    @DisplayName("【资金安全】累计退款不得超过原金额 —— 并发超额退款的核心防护")
    void cannotRefundMoreThanOriginalAmount() {
        order.applyChannelResult(outTradeNo, true, AMOUNT, "WX_TXN_1", "SUCCESS", null);
        assertEquals(PaymentStatus.PAID, order.status());

        // 第一次退 60，合法
        order.requestRefund(Money.ofMajor("60.00", Currency.CNY), "退部分", 0);
        assertEquals(PaymentStatus.PARTIALLY_REFUNDED, order.status());
        assertEquals(Money.ofMajor("40.00", Currency.CNY), order.refundableAmount());

        // 第二次退 50，累计 110 > 100，必须被拒绝
        assertThrows(IllegalStateException.class,
                () -> order.requestRefund(Money.ofMajor("50.00", Currency.CNY), "再退一次", 0),
                "累计退款超过原金额时必须拒绝");
    }

    @Test
    @DisplayName("全额退款后订单进入终态 REFUNDED")
    void fullRefundReachesTerminalState() {
        order.applyChannelResult(outTradeNo, true, AMOUNT, "WX_TXN_1", "SUCCESS", null);
        order.requestRefund(AMOUNT, "全额退", 0);

        assertEquals(PaymentStatus.REFUNDED, order.status());
        assertTrue(order.status().isTerminal());
        assertEquals(0L, order.refundableAmount().minorUnits());
    }

    @Test
    @DisplayName("【资金安全】回调乱序：先收到成功再收到失败，必须保持成功状态并告警")
    void outOfOrderNotificationMustNotRevertPaidState() {
        order.applyChannelResult(outTradeNo, true, AMOUNT, "WX_TXN_1", "SUCCESS", null);
        order.pullDomainEvents(); // 清空创建期事件
        assertEquals(PaymentStatus.PAID, order.status());

        // 迟到的失败通知（通道补偿推送 / 网络重投）
        boolean changed = order.applyChannelResult(outTradeNo, false, null, null, "PAYERROR", null);

        assertFalse(changed, "乱序的失败回调不应改变已支付状态");
        assertEquals(PaymentStatus.PAID, order.status(), "状态必须保持为已支付");

        // 关键：不能静默吞掉，必须产生告警事件
        // （若抛异常会触发通道无限重投；若静默吞掉则事故无人知晓）
        boolean hasAlert = order.pullDomainEvents().stream()
                .anyMatch(e -> e instanceof com.demo.payment.domain.acquiring.event.SuspiciousNotificationReceived);
        assertTrue(hasAlert, "可疑通知必须产生告警事件，供监控与人工核查");
    }

    @Test
    @DisplayName("【资金安全】回调金额被篡改时必须拒绝（1 分钱买 1000 元商品的攻击）")
    void tamperedAmountInNotificationMustBeRejected() {
        Money tampered = Money.ofMajor("0.01", Currency.CNY);
        assertThrows(IllegalStateException.class,
                () -> order.applyChannelResult(outTradeNo, true, tampered, "TXN", "SUCCESS", null),
                "回调金额与订单金额不一致时必须拒绝");
    }

    @Test
    @DisplayName("不属于本订单的 outTradeNo 回调必须被拒绝")
    void foreignOutTradeNoRejected() {
        assertThrows(IllegalStateException.class,
                () -> order.applyChannelResult(OutTradeNo.of("OTHER_ORDER"), true,
                        AMOUNT, "TXN", "SUCCESS", null));
    }

    @Test
    @DisplayName("已支付的订单不能被关闭，必须先退款")
    void paidOrderCannotBeClosed() {
        order.applyChannelResult(outTradeNo, true, AMOUNT, "WX_TXN_1", "SUCCESS", null);
        assertThrows(IllegalStateException.class, () -> order.close("超时关单"),
                "已支付订单关闭会导致钱货两空，必须拒绝");
    }

    @Test
    @DisplayName("重复的成功回调是幂等的，不产生重复事件")
    void duplicateSuccessNotificationIsIdempotent() {
        order.applyChannelResult(outTradeNo, true, AMOUNT, "WX_TXN_1", "SUCCESS", null);
        order.pullDomainEvents(); // 清空

        boolean changed = order.applyChannelResult(outTradeNo, true, AMOUNT,
                "WX_TXN_1", "SUCCESS", null);

        assertFalse(changed, "重复通知不应产生状态变更");
        assertTrue(order.pullDomainEvents().isEmpty(), "重复通知不应产生重复事件");
    }

    @Test
    @DisplayName("多次通道尝试各自生成独立的 outTradeNo，互不冲突")
    void multipleAttemptsHaveDistinctOutTradeNo() {
        OutTradeNo second = OutTradeNo.of("P202601010000001A2");
        assertDoesNotThrow(() -> order.startAttempt(ChannelCode.ALIPAY, second));
        assertEquals(2, order.attempts().size());

        // 重复注册同一个 outTradeNo 必须被拒绝
        assertThrows(IllegalStateException.class,
                () -> order.startAttempt(ChannelCode.ALIPAY, second));
    }

    @Test
    @DisplayName("退款超出通道期限时必须被拒绝（构造 200 天前的订单，通道期限 180 天）")
    void refundBeyondWindowRejected() {
        Instant twoHundredDaysAgo = Instant.now().minus(200, ChronoUnit.DAYS);
        PaymentOrder oldOrder = PaymentOrder.reconstitute(
                com.demo.payment.domain.acquiring.model.aggregate.PaymentOrderId.of("P_OLD_ORDER"),
                MERCHANT_ID, "ORDER_OLD", AMOUNT, PaymentMethodType.WECHAT_PAY,
                "超期商品", null, null, PaymentStatus.PAID,
                List.of(), List.of(), 0L, twoHundredDaysAgo, twoHundredDaysAgo);

        // 通道退款期限 180 天，200 天前支付的订单已超期
        assertThrows(IllegalStateException.class,
                () -> oldOrder.requestRefund(Money.ofMajor("10.00", Currency.CNY), "超期退款", 180),
                "超过通道退款期限必须拒绝，需转人工差错流程");
    }

    @Test
    @DisplayName("未超期的退款正常受理")
    void refundWithinWindowAccepted() {
        order.applyChannelResult(outTradeNo, true, AMOUNT, "WX_TXN_1", "SUCCESS", null);
        // 刚支付的订单，距今天数为 0，远小于 180 天期限
        assertDoesNotThrow(() ->
                order.requestRefund(Money.ofMajor("10.00", Currency.CNY), "正常退款", 180));
        assertEquals(PaymentStatus.PARTIALLY_REFUNDED, order.status());
    }
}
