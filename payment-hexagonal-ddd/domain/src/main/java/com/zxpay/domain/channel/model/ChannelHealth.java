package com.zxpay.domain.channel.model;

import java.time.Instant;

/**
 * 通道运行期健康度（动态指标）。
 *
 * <p>与 {@link ChannelCapability}（静态能力）互补：能力决定「能不能接」，
 * 健康度决定「现在该不该接」。二者必须分开建模——能力几天变一次，
 * 健康度几秒就变一次，放一起会让缓存策略无法设计。
 *
 * <p>典型用途：某通道连续失败 N 笔后自动降权甚至摘除，避免雪崩时
 * 还在把流量往已经挂掉的通道上打。
 */
public record ChannelHealth(
        ChannelCode channel,

        /** 近期成功率，0~1。 */
        double successRate,

        /** 平均响应耗时（毫秒）。 */
        long avgLatencyMillis,

        /** 连续失败次数。熔断判定的核心依据。 */
        int consecutiveFailures,

        /** 是否处于熔断/降级状态。 */
        boolean degraded,

        /** 指标采集时间。过期的健康度应视为未知而非可信。 */
        Instant measuredAt
) {

    /** 健康度数据是否过期。过期数据不应作为降权依据。 */
    public boolean isStale(Instant now, java.time.Duration maxAge) {
        return measuredAt == null || measuredAt.plus(maxAge).isBefore(now);
    }

    /** 是否应当被熔断摘除。 */
    public boolean shouldBeIsolated(int failureThreshold) {
        return degraded || consecutiveFailures >= failureThreshold;
    }

    public static ChannelHealth unknown(ChannelCode channel) {
        return new ChannelHealth(channel, 1.0d, 0L, 0, false, null);
    }
}
