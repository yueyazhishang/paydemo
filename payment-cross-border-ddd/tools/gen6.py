#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成单元测试"""
import os

BASE = "/Users/abc/WorkBuddy/2026-08-29-13-23-42/payment-ddd-demo"
F = {}

F["payment-shared-kernel/src/test/java/com/demo/payment/shared/MoneyTest.java"] = r'''
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
'''

F["payment-domain/src/test/java/com/demo/payment/domain/PaymentOrderTest.java"] = r'''
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
'''

F["payment-domain/src/test/java/com/demo/payment/domain/PaymentStateMachineTest.java"] = r'''
package com.demo.payment.domain;

import com.demo.payment.domain.acquiring.statemachine.PaymentStateMachine;
import com.demo.payment.domain.acquiring.statemachine.PaymentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 状态机测试 —— 状态机是支付系统防资损的最后一道闸门。
 */
class PaymentStateMachineTest {

    @Test
    @DisplayName("合法路径：CREATED → PAYING → PAID → PARTIALLY_REFUNDED → REFUNDED")
    void legalPath() {
        assertDoesNotThrow(() -> {
            PaymentStateMachine.validate(PaymentStatus.CREATED, PaymentStatus.PAYING);
            PaymentStateMachine.validate(PaymentStatus.PAYING, PaymentStatus.PAID);
            PaymentStateMachine.validate(PaymentStatus.PAID, PaymentStatus.PARTIALLY_REFUNDED);
            PaymentStateMachine.validate(PaymentStatus.PARTIALLY_REFUNDED, PaymentStatus.REFUNDED);
        });
    }

    @Test
    @DisplayName("终态不可变：REFUNDED 不能变回 PAID")
    void terminalStateImmutable() {
        assertThrows(IllegalStateException.class,
                () -> PaymentStateMachine.validate(PaymentStatus.REFUNDED, PaymentStatus.PAID));
        assertThrows(IllegalStateException.class,
                () -> PaymentStateMachine.validate(PaymentStatus.CLOSED, PaymentStatus.PAYING));
        assertThrows(IllegalStateException.class,
                () -> PaymentStateMachine.validate(PaymentStatus.FAILED, PaymentStatus.PAID));
    }

    @Test
    @DisplayName("不存在状态回退：PAID 不能回到 PAYING")
    void noBackwardTransition() {
        assertThrows(IllegalStateException.class,
                () -> PaymentStateMachine.validate(PaymentStatus.PAID, PaymentStatus.PAYING),
                "支付成功后不能回退到支付中");
    }

    @Test
    @DisplayName("未支付不能退款")
    void cannotRefundBeforePaid() {
        assertThrows(IllegalStateException.class,
                () -> PaymentStateMachine.validate(PaymentStatus.CREATED, PaymentStatus.REFUNDED));
    }

    @Test
    @DisplayName("相同状态是幂等的，不视为非法转换")
    void sameStateIsIdempotent() {
        assertDoesNotThrow(() ->
                PaymentStateMachine.validate(PaymentStatus.PAID, PaymentStatus.PAID),
                "重复回调到达相同状态应被放过，这是幂等的基础");
    }

    @Test
    @DisplayName("两段式路径：PAYING → AUTHORIZED → CAPTURING → PAID")
    void twoPhasePath() {
        assertDoesNotThrow(() -> {
            PaymentStateMachine.validate(PaymentStatus.PAYING, PaymentStatus.AUTHORIZED);
            PaymentStateMachine.validate(PaymentStatus.AUTHORIZED, PaymentStatus.CAPTURING);
            PaymentStateMachine.validate(PaymentStatus.CAPTURING, PaymentStatus.PAID);
        });
    }
}
'''

F["payment-channel-adapter/src/test/java/com/demo/payment/adapter/ChannelCapabilityTest.java"] = r'''
package com.demo.payment.adapter;

import com.demo.payment.adapter.alipay.AlipayAdapter;
import com.demo.payment.adapter.applepay.ApplePayAdapter;
import com.demo.payment.adapter.stripe.StripeAdapter;
import com.demo.payment.adapter.wechatpay.WechatPayAdapter;
import com.demo.payment.adapter.worldpay.WorldpayAdapter;
import com.demo.payment.domain.channel.model.*;
import com.demo.payment.domain.channel.route.CapabilityBasedRouter;
import com.demo.payment.domain.channel.route.WeightedRouteStrategy;
import com.demo.payment.domain.channel.spi.RoutingContext;
import com.demo.payment.shared.money.Currency;
import com.demo.payment.shared.money.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 通道能力矩阵与路由测试 —— 验证"能力声明驱动"而非"if-else 判断"的设计。
 */
class ChannelCapabilityTest {

    private WechatPayAdapter wechat;
    private AlipayAdapter alipay;
    private StripeAdapter stripe;
    private WorldpayAdapter worldpay;

    @BeforeEach
    void setUp() {
        wechat = new WechatPayAdapter();
        alipay = new AlipayAdapter();
        stripe = new StripeAdapter();
        worldpay = new WorldpayAdapter();
    }

    @Test
    @DisplayName("【核心认知】Apple Pay 不是通道，必须委托给收单行执行")
    void applePayDelegatesToAcquirer() {
        ApplePayAdapter applePay = new ApplePayAdapter(stripe);

        // channelCode 返回的是委托对象的编码 —— 真正扣款的是 Stripe，不是 Apple
        assertEquals(ChannelCode.STRIPE, applePay.channelCode(),
                "Apple Pay 的资金流由底层收单行承载");

        // 能力继承自委托方
        assertEquals(stripe.capability().supportsChargeback(),
                applePay.capability().supportsChargeback());

        // 但支付方式被限定为 Apple Pay
        assertTrue(applePay.capability().supports(PaymentMethodType.APPLE_PAY));
        assertFalse(applePay.capability().supports(PaymentMethodType.BANK_CARD));
    }

    @Test
    @DisplayName("Apple Pay 不能委托给不支持它的通道")
    void applePayRejectsUnsupportedDelegate() {
        assertThrows(IllegalArgumentException.class,
                () -> new ApplePayAdapter(wechat),
                "微信不支持 Apple Pay，不能作为委托目标");
    }

    @Test
    @DisplayName("Apple Pay 可切换委托目标实现容灾（Stripe → Worldpay）")
    void applePayDelegateCanBeSwitched() {
        ApplePayAdapter viaStripe = new ApplePayAdapter(stripe);
        ApplePayAdapter viaWorldpay = new ApplePayAdapter(worldpay);

        assertEquals(ChannelCode.STRIPE, viaStripe.channelCode());
        assertEquals(ChannelCode.WORLDPAY, viaWorldpay.channelCode(),
                "同一支付方式可切换不同收单行 —— 这是解耦带来的容灾能力");
    }

    @Test
    @DisplayName("国内钱包通道是一段式，不支持请款")
    void domesticWalletIsSinglePhase() {
        assertFalse(wechat.capability().authCaptureSeparated());
        assertFalse(wechat.capability().requiresExplicitCapture());
        assertFalse(alipay.capability().authCaptureSeparated());
    }

    @Test
    @DisplayName("卡收单通道是两段式，支持授权后请款")
    void cardAcquirerIsTwoPhase() {
        assertTrue(stripe.capability().authCaptureSeparated());
        assertTrue(worldpay.capability().authCaptureSeparated());
        assertTrue(stripe.capability().requiresExplicitCapture());
    }

    @Test
    @DisplayName("微信不支持撤销，支付宝支持 —— 这是真实的通道差异")
    void cancelSupportDiffers() {
        assertFalse(wechat.capability().supportsCancel(), "微信支付不支持撤销，只能退款");
        assertTrue(alipay.capability().supportsCancel(), "支付宝支持 alipay.trade.cancel");
    }

    @Test
    @DisplayName("拒付只有卡组织通道才有，国内钱包没有这个概念")
    void chargebackOnlyForCardSchemes() {
        assertTrue(stripe.capability().supportsChargeback());
        assertTrue(worldpay.capability().supportsChargeback());
        assertFalse(wechat.capability().supportsChargeback());
        assertFalse(alipay.capability().supportsChargeback());
    }

    @Test
    @DisplayName("幂等机制三种形态：Stripe 请求头、Antom 业务字段、微信仅订单号")
    void idempotencyModesDiffer() {
        assertEquals(ChannelCapability.IdempotencyMode.HEADER_IDEMPOTENCY_KEY,
                stripe.capability().idempotencyMode());
        assertEquals(ChannelCapability.IdempotencyMode.MERCHANT_ORDER_NO_ONLY,
                wechat.capability().idempotencyMode(),
                "微信无幂等头，重试前必须先查单");
    }

    @Test
    @DisplayName("Worldpay 用 XML + MAC，签名算法与其他通道完全不同")
    void worldpayUsesXmlAndMac() {
        assertEquals(ChannelCapability.SignatureAlgorithm.WORLDPAY_MAC,
                worldpay.capability().signatureAlgorithm());
    }

    @Test
    @DisplayName("路由：CNY + 微信支付只能选出国内通道")
    void routingDomesticPayment() {
        CapabilityBasedRouter router = new CapabilityBasedRouter(new WeightedRouteStrategy());
        router.register(wechat.capability())
              .register(alipay.capability())
              .register(stripe.capability())
              .register(worldpay.capability());

        RoutingContext ctx = new RoutingContext("M001", PaymentMethodType.WECHAT_PAY,
                Money.ofMajor("100", Currency.CNY), Currency.CNY, "CN", null, "APP");

        List<ChannelCode> routes = router.route(ctx);
        assertTrue(routes.contains(ChannelCode.WECHAT_PAY));
        assertFalse(routes.contains(ChannelCode.STRIPE), "Stripe 不支持微信支付方式");
    }

    @Test
    @DisplayName("路由：USD + Apple Pay 能选出卡收单通道")
    void routingApplePayInUsd() {
        CapabilityBasedRouter router = new CapabilityBasedRouter(new WeightedRouteStrategy());
        router.register(wechat.capability())
              .register(stripe.capability())
              .register(worldpay.capability());

        RoutingContext ctx = new RoutingContext("M001", PaymentMethodType.APPLE_PAY,
                Money.ofMajor("10", Currency.USD), Currency.USD, "US", null, "APP");

        List<ChannelCode> routes = router.route(ctx);
        assertTrue(routes.contains(ChannelCode.STRIPE));
        assertTrue(routes.contains(ChannelCode.WORLDPAY));
        assertFalse(routes.contains(ChannelCode.WECHAT_PAY), "微信不支持 Apple Pay 与 USD");
    }

    @Test
    @DisplayName("路由诊断：能解释每个通道为何被过滤 —— 生产排查利器")
    void routingExplain() {
        CapabilityBasedRouter router = new CapabilityBasedRouter(new WeightedRouteStrategy());
        router.register(wechat.capability()).register(stripe.capability());

        RoutingContext ctx = new RoutingContext("M001", PaymentMethodType.BANK_CARD,
                Money.ofMajor("10", Currency.USD), Currency.USD, "US", null, "APP");

        Map<ChannelCode, String> explain = router.explain(ctx);
        assertEquals("AVAILABLE", explain.get(ChannelCode.STRIPE));
        assertTrue(explain.get(ChannelCode.WECHAT_PAY).contains("不支持"),
                "微信被过滤的原因必须可解释");
    }

    @Test
    @DisplayName("金额超出通道限额时路由应过滤掉该通道")
    void routingFiltersByAmountLimit() {
        CapabilityBasedRouter router = new CapabilityBasedRouter(new WeightedRouteStrategy());
        router.register(wechat.capability());

        // 微信单笔上限设置为 5000 万分（50 万元），构造超限金额
        RoutingContext ctx = new RoutingContext("M001", PaymentMethodType.WECHAT_PAY,
                Money.ofMinor(99_999_999_999L, Currency.CNY), Currency.CNY, "CN", null, "APP");

        assertTrue(router.route(ctx).isEmpty(), "超限金额应无可用通道");
    }

    @Test
    @DisplayName("调用不支持的撤销操作时，返回结构化失败而非抛异常")
    void unsupportedCancelReturnsFailure() {
        var response = wechat.cancel(
                new com.demo.payment.domain.channel.spi.CancelCommand(
                        com.demo.payment.domain.acquiring.model.vo.OutTradeNo.of("TEST001"),
                        "TXN", "test"));

        assertFalse(response.cancelled());
        assertEquals("CANCEL_UNSUPPORTED", response.code());
    }
}
'''

for path, content in F.items():
    full = os.path.join(BASE, path)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    with open(full, "w", encoding="utf-8") as f:
        f.write(content.lstrip("\n"))
    print("WROTE", path)
print("\nTOTAL:", len(F))
