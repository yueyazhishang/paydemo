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
