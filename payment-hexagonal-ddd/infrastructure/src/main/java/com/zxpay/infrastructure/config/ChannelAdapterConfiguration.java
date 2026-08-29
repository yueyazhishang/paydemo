package com.zxpay.infrastructure.config;

import com.zxpay.infrastructure.channel.adapter.AlipayAdapter;
import com.zxpay.infrastructure.channel.adapter.AntomAdapter;
import com.zxpay.infrastructure.channel.adapter.ApplePayAdapter;
import com.zxpay.infrastructure.channel.adapter.JdPayAdapter;
import com.zxpay.infrastructure.channel.adapter.PayPalAdapter;
import com.zxpay.infrastructure.channel.adapter.StripeAdapter;
import com.zxpay.infrastructure.channel.adapter.UnionPayAdapter;
import com.zxpay.infrastructure.channel.adapter.WeChatPayAdapter;
import com.zxpay.infrastructure.channel.adapter.WorldpayAdapter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 通道适配器的装配。
 *
 * <p><b>这个文件刻意放在基础设施层，而不是接口层。</b>
 *
 * <p>理由：接口层的 pom 里，infrastructure 是 {@code runtime} 作用域——
 * 编译期接口层看不到任何适配器类。这就从<b>构建层面</b>强制了
 * 「入站适配器不许直接依赖出站适配器实现」这条规则。
 *
 * <p>而 Spring 的组件扫描在运行时会跨模块收集 Bean，
 * 因此启动后注册表依然能拿到全部适配器。
 * 用构建作用域表达架构约束，比靠 Code Review 提醒有效得多。
 *
 * <p><b>接一家新通道要改哪里？就这里加一个 @Bean，再加一份能力配置。</b>
 * 业务代码一行不动——这就是六边形架构 + 能力矩阵想要的效果。
 */
@Configuration
public class ChannelAdapterConfiguration {

    @Bean public WeChatPayAdapter weChatPayAdapter() { return new WeChatPayAdapter(); }
    @Bean public AlipayAdapter alipayAdapter() { return new AlipayAdapter(); }
    @Bean public JdPayAdapter jdPayAdapter() { return new JdPayAdapter(); }
    @Bean public UnionPayAdapter unionPayAdapter() { return new UnionPayAdapter(); }
    @Bean public StripeAdapter stripeAdapter() { return new StripeAdapter(); }
    @Bean public PayPalAdapter payPalAdapter() { return new PayPalAdapter(); }
    @Bean public AntomAdapter antomAdapter() { return new AntomAdapter(); }
    @Bean public WorldpayAdapter worldpayAdapter() { return new WorldpayAdapter(); }

    /**
     * Apple Pay 适配器。
     *
     * <p>注意它<b>不是</b> {@code ChannelPaymentPort} 的实现——
     * 钱包不收单，没有下单能力。注册它只是为了让注册表能提供
     * 它的回调验签与解析能力。
     */
    @Bean public ApplePayAdapter applePayAdapter() { return new ApplePayAdapter(); }
}
