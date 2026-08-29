package com.demo.payment.infra.config;

import com.demo.payment.adapter.alipay.AlipayAdapter;
import com.demo.payment.adapter.antom.AntomAdapter;
import com.demo.payment.adapter.applepay.ApplePayAdapter;
import com.demo.payment.adapter.core.ChannelRegistry;
import com.demo.payment.adapter.jdpay.JdPayAdapter;
import com.demo.payment.adapter.paypal.PayPalAdapter;
import com.demo.payment.adapter.stripe.StripeAdapter;
import com.demo.payment.adapter.unionpay.UnionPayAdapter;
import com.demo.payment.adapter.wechatpay.WechatPayAdapter;
import com.demo.payment.adapter.worldpay.WorldpayAdapter;
import com.demo.payment.domain.channel.model.ChannelCode;
import com.demo.payment.domain.channel.route.CapabilityBasedRouter;
import com.demo.payment.domain.channel.route.ChannelRouter;
import com.demo.payment.domain.channel.route.WeightedRouteStrategy;
import com.demo.payment.domain.channel.spi.PaymentChannelPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.EnumMap;
import java.util.Map;

/**
 * 通道装配配置 —— <b>新增通道只需在这里加一个 @Bean</b>。
 *
 * <p>这里最能体现"开闭原则"：所有通道实现 {@link PaymentChannelPort}，
 * 由 Spring 收集注入，路由/支付/退款/查证等上层逻辑<b>零改动</b>。
 *
 * <h3>Apple Pay 的装配是特殊的一例</h3>
 * <p>它<b>必须注入一个底层收单行</b>（此处为 StripeAdapter）作为委托对象。
 * 这直观体现了"Apple Pay 不是通道"这一建模认知：
 * 它在 Spring 容器里是一个 Bean，但它寄生于另一个通道。
 *
 * <p>如果要容灾切换（Stripe → Worldpay），只需改这一行注入，
 * 或者实现按健康度动态选择委托目标 —— 这在"Apple Pay 是通道"
 * 的错误建模下是不可能做到的。
 */
@Configuration
public class PaymentChannelConfiguration {

    // ---------- 国内通道 ----------

    @Bean
    public WechatPayAdapter wechatPayAdapter() {
        return new WechatPayAdapter();
    }

    @Bean
    public AlipayAdapter alipayAdapter() {
        return new AlipayAdapter();
    }

    @Bean
    public JdPayAdapter jdPayAdapter() {
        return new JdPayAdapter();
    }

    @Bean
    public UnionPayAdapter unionPayAdapter() {
        return new UnionPayAdapter();
    }

    // ---------- 海外通道 ----------

    @Bean
    public PayPalAdapter payPalAdapter() {
        return new PayPalAdapter();
    }

    @Bean
    public StripeAdapter stripeAdapter() {
        return new StripeAdapter();
    }

    @Bean
    public WorldpayAdapter worldpayAdapter() {
        return new WorldpayAdapter();
    }

    @Bean
    public AntomAdapter antomAdapter() {
        return new AntomAdapter();
    }

    /**
     * Apple Pay —— 委托给 Stripe 收单。
     *
     * <p>若 Stripe 不可用，可改为委托 Worldpay：
     * {@code new ApplePayAdapter(worldpayAdapter())}
     * 这正是"支付方式与通道解耦"带来的容灾能力。
     */
    @Bean
    public ApplePayAdapter applePayAdapter(StripeAdapter stripeAdapter) {
        return new ApplePayAdapter(stripeAdapter);
    }

    // ---------- 路由装配 ----------

    @Bean
    public CapabilityBasedRouter channelRouter(java.util.List<PaymentChannelPort> channels) {
        CapabilityBasedRouter router = new CapabilityBasedRouter(new WeightedRouteStrategy());
        // 注册所有通道的能力矩阵 —— 路由的硬过滤完全依赖这些数据
        channels.forEach(ch -> router.register(ch.capability()));
        return router;
    }

    /**
     * 通道索引表。
     *
     * <p><b>注意：Apple Pay 的 channelCode() 返回的是 STRIPE</b>（其委托对象），
     * 因此它会覆盖掉 Stripe 原生实例的映射。这是故意的：
     * 当上层按 {@code ChannelCode.STRIPE} 取通道时，
     * 需要根据具体支付方式选择"原生卡支付"还是"Apple Pay 委托"。
     *
     * <p>生产环境的正确做法是按 {@code (channelCode, paymentMethod)} 二元组索引，
     * 此处为演示清晰，采用"后注册覆盖"策略并保留 registry 供精确查找。
     */
    @Bean
    public Map<ChannelCode, PaymentChannelPort> channelMap(java.util.List<PaymentChannelPort> channels) {
        Map<ChannelCode, PaymentChannelPort> map = new EnumMap<>(ChannelCode.class);
        channels.forEach(ch -> map.put(ch.channelCode(), ch));
        return map;
    }

    @Bean
    public ChannelRegistry channelRegistry(java.util.List<PaymentChannelPort> channels) {
        ChannelRegistry registry = new ChannelRegistry();
        channels.forEach(registry::register);
        return registry;
    }
}
