package com.zxpay.sharedkernel.time;

import java.time.Clock;
import java.time.Instant;

/**
 * 时钟持有者。
 *
 * <p>领域层禁止直接调用 {@code Instant.now()} / {@code LocalDateTime.now()}。
 * 原因很实际：支付领域大量规则与时间相关——支付单超时关闭（微信预支付 2 小时）、
 * 退款窗口（部分通道 180 天）、授权有效期（卡组 7 天）、幂等键 24 小时过期。
 * 若时间不可控，这些规则几乎无法做确定性单测。
 *
 * <p>生产由 Spring 注入 {@code Clock.systemUTC()}，测试用 {@code Clock.fixed(...)}
 * 把时间钉死，即可精确验证「第 121 分钟支付单是否被关闭」这类断言。
 */
public final class ClockHolder {

    private static Clock clock = Clock.systemUTC();

    private ClockHolder() {
    }

    public static Clock clock() {
        return clock;
    }

    public static Instant now() {
        return clock.instant();
    }

    public static long currentTimeMillis() {
        return clock.millis();
    }

    /** 由启动装配或测试基类调用。 */
    public static void setClock(Clock newClock) {
        clock = newClock == null ? Clock.systemUTC() : newClock;
    }

    /** 测试收尾务必调用，避免污染其他用例。 */
    public static void reset() {
        clock = Clock.systemUTC();
    }
}
