package com.zxpay.domain.channel.port;

import com.zxpay.domain.channel.model.ChannelCapability;
import com.zxpay.domain.channel.model.ChannelCode;

import java.util.Collection;
import java.util.Optional;

/**
 * 出站端口：通道能力查询。
 *
 * <p>领域层只知道「能问到通道能力」，完全不知道能力来自配置中心、数据库还是硬编码的
 * Spring 配置。基础设施层提供实现（本 Demo 中是
 * {@code InMemoryChannelCapabilityQuery}，生产里是配置中心 + DB + 本地缓存）。
 *
 * <p>这就是六边形架构右侧端口的意义：领域定义<b>需要什么</b>，适配器决定<b>怎么来</b>。
 * 单测时直接给个 Map 实现即可，不需要任何 Spring 容器或数据库。
 */
public interface ChannelCapabilityQuery {

    Optional<ChannelCapability> findByChannel(ChannelCode channel);

    /** 所有已启用的通道能力。 */
    Collection<ChannelCapability> findAllEnabled();

    /** 批量查询，供路由时一次性加载，避免 N 次单查。 */
    default java.util.Map<ChannelCode, ChannelCapability> asMap() {
        java.util.Map<ChannelCode, ChannelCapability> map = new java.util.EnumMap<>(ChannelCode.class);
        for (ChannelCapability capability : findAllEnabled()) {
            map.put(capability.channel(), capability);
        }
        return map;
    }
}
