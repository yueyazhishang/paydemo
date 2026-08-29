package com.demo.payment.adapter.core;

import com.demo.payment.domain.channel.model.ChannelCode;
import com.demo.payment.domain.channel.spi.PaymentChannelPort;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 通道注册中心。
 *
 * <p>这是一个典型的<b>注册表模式</b>，作用是让"新增通道"这件事变成
 * <b>增加一个 Bean，而不是修改一堆 if-else</b>。
 *
 * <p>配合 Spring 的 {@code List<PaymentChannelPort>} 自动注入，
 * 新增通道只需：
 * <ol>
 *   <li>写一个实现 {@link PaymentChannelPort} 的类</li>
 *   <li>加上 {@code @Component}</li>
 * </ol>
 * 路由、退款、查证等所有上层逻辑零改动 —— 这就是开闭原则的落地。
 *
 * <p><b>注意 Apple Pay 的特殊性</b>：它注册进来时 channelCode() 返回的是
 * 底层 PSP 的编码（因为它寄生于 PSP），因此注册表按
 * {@code (channelCode, paymentMethod)} 二元组索引，而非仅按 channelCode。
 */
public class ChannelRegistry {

    private final Map<ChannelCode, PaymentChannelPort> byCode = new ConcurrentHashMap<>();

    public void register(PaymentChannelPort port) {
        byCode.put(port.channelCode(), port);
    }

    public Optional<PaymentChannelPort> get(ChannelCode code) {
        return Optional.ofNullable(byCode.get(code));
    }

    /**
     * 按支付方式查找支持它的通道。
     *
     * @return 支持该支付方式的通道列表
     */
    public List<PaymentChannelPort> findByPaymentMethod(
            com.demo.payment.domain.channel.model.PaymentMethodType method) {
        return byCode.values().stream()
                .filter(p -> p.capability().supports(method))
                .collect(java.util.stream.Collectors.toList());
    }

    public Collection<PaymentChannelPort> all() {
        return Collections.unmodifiableCollection(byCode.values());
    }

    public int size() { return byCode.size(); }
}
