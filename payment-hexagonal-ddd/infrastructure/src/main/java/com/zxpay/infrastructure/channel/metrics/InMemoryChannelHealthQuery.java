package com.zxpay.infrastructure.channel.metrics;

import com.zxpay.domain.channel.model.ChannelCode;
import com.zxpay.domain.channel.model.ChannelHealth;
import com.zxpay.domain.channel.port.ChannelHealthQuery;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;

/**
 * 通道健康度的内存实现。
 *
 * <p>生产环境这里接的是监控指标（Prometheus 的滑动窗口成功率）、
 * Redis 里的实时计数器，或专门的通道质量服务。
 * 领域层不关心数据来源，只关心「这家通道现在健康吗」。
 *
 * <p>预设数据刻意让 Worldpay 处于降级状态：
 * 这样在演示路由时能直观看到——即便它费率最低，
 * 也会因健康度惩罚被排到后面。这正是「路由必须把健康度算进去」的理由。
 */
@Component
public class InMemoryChannelHealthQuery implements ChannelHealthQuery {

    private final Map<ChannelCode, ChannelHealth> healths = new EnumMap<>(ChannelCode.class);

    public InMemoryChannelHealthQuery() {
        Instant now = Instant.now();
        put(ChannelCode.WECHAT_PAY, 0.995d, 180L, 0, false, now);
        put(ChannelCode.ALIPAY, 0.993d, 210L, 0, false, now);
        put(ChannelCode.JD_PAY, 0.970d, 450L, 1, false, now);
        put(ChannelCode.UNIONPAY, 0.980d, 320L, 0, false, now);
        put(ChannelCode.STRIPE, 0.988d, 380L, 0, false, now);
        put(ChannelCode.PAYPAL, 0.982d, 520L, 0, false, now);
        put(ChannelCode.ANTOM, 0.985d, 400L, 0, false, now);
        // Worldpay 连续失败 12 次并已降级：演示熔断降权
        put(ChannelCode.WORLDPAY, 0.820d, 1600L, 12, true, now);
        put(ChannelCode.APPLE_PAY, 1.0d, 120L, 0, false, now);
    }

    private void put(ChannelCode channel, double successRate, long latency,
                     int failures, boolean degraded, Instant at) {
        healths.put(channel, new ChannelHealth(channel, successRate, latency, failures, degraded, at));
    }

    @Override
    public ChannelHealth healthOf(ChannelCode channel) {
        return healths.getOrDefault(channel, ChannelHealth.unknown(channel));
    }

    @Override
    public Collection<ChannelHealth> healthOfAll() {
        return healths.values();
    }
}
