package com.zxpay.infrastructure.config;

import com.zxpay.domain.channel.port.ChannelCapabilityQuery;
import com.zxpay.domain.channel.port.ChannelHealthQuery;
import com.zxpay.domain.channel.service.ChannelCapabilityRegistry;
import com.zxpay.domain.payment.service.ChannelRoutingService;
import com.zxpay.infrastructure.channel.config.ChannelCapabilityConfiguration;
import com.zxpay.infrastructure.channel.metrics.InMemoryChannelHealthQuery;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 领域服务的装配。
 *
 * <p>领域服务本身不依赖框架（构造注入即可），
 * 但需要有人把它们和具体实现（端口适配器）拼起来——
 * 这个「拼装」的职责就落在基础设施层的配置类上。
 *
 * <p>这正是依赖注入在六边形架构里的作用：
 * <b>领域层定义需要什么端口，基础设施层决定给什么实现</b>，
 * 两者通过配置解耦。单测时换成 Map 实现的假对象即可，
 * 不需要 Spring 容器。
 */
@Configuration
public class DomainServiceConfiguration {

    @Bean
    public ChannelCapabilityQuery channelCapabilityQuery() {
        return new ChannelCapabilityConfiguration();
    }

    @Bean
    public ChannelHealthQuery channelHealthQuery() {
        return new InMemoryChannelHealthQuery();
    }

    /**
     * 能力注册中心：只回答「能不能接」。
     *
     * <p>与 {@link ChannelRoutingService} 分工明确，
     * 后者回答「选哪家」。拆开是为了让路由策略能独立演进。
     */
    @Bean
    public ChannelCapabilityRegistry channelCapabilityRegistry(ChannelCapabilityQuery query) {
        return new ChannelCapabilityRegistry(query);
    }

    @Bean
    public ChannelRoutingService channelRoutingService(ChannelCapabilityRegistry registry,
                                                       ChannelHealthQuery healthQuery) {
        return new ChannelRoutingService(registry, healthQuery);
    }
}
