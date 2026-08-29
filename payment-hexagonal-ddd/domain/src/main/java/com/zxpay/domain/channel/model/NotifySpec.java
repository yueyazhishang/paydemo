package com.zxpay.domain.channel.model;

import java.time.Duration;

/**
 * 通道异步通知规范。
 *
 * <p>这是「下单成功」和「支付成功」之间的那段灰色地带。所有通道都不可靠，
 * 但不可靠的<b>方式</b>不同，处理方式也因此不同：
 *
 * <ul>
 *   <li><b>会重复</b>：几乎所有通道都是「至少一次」投递，且失败后按递增间隔重试
 *       （微信是 15s/15s/30s/3m/10m/20m/30m/30m/30m/60m/3h/3h/3h/6h/6h/6h，共 15 次）。
 *       消费端必须幂等。</li>
 *   <li><b>会乱序</b>：网络重放、重试、多实例消费都可能让「成功」通知晚于「失败」通知到达，
 *       或新旧通知交错。必须靠状态机守卫，而不是简单地按到达顺序覆盖。</li>
 *   <li><b>会丢失</b>：这是最致命的。通道不会无限重试，商户回调地址抖动几分钟就可能全部丢失。
 *       因此<b>主动查单补偿是必需能力，不是可选兜底</b>——见
 *       {@code PendingPaymentReconciliationUseCase}。</li>
 * </ul>
 *
 * <p>{@code pullOnly} 的通道（如银行转账）压根没有推送，只能靠轮询。
 */
public record NotifySpec(
        /** 通知投递方式。 */
        NotifyMode mode,

        /** 通知是否带签名。即便带签名也必须验，否则任何人都能伪造「支付成功」。 */
        boolean signed,

        /** 至少一次投递：必然出现重复，消费端需幂等。 */
        boolean atLeastOnce,

        /** 可能乱序：需用状态机 + 通道事件时间戳守卫，不能简单覆盖。 */
        boolean outOfOrder,

        /** 首次通知相对支付成功的典型延迟。 */
        Duration typicalDelay,

        /** 最大重试次数。超过后通道不再推送，只能靠主动查单发现。 */
        int maxRetries,

        /** 重试耗尽的总时长。补偿任务应在此时间之后介入。 */
        Duration retryWindow
) {

    public enum NotifyMode {
        /** 通道主动推送到我们的回调地址。 */
        PUSH,

        /** 无推送，只能主动查单（银行转账、部分线下支付）。 */
        PULL,

        /** 两者皆有，推送为主、查单兜底。 */
        BOTH,
    }

    /** 是否存在「通知彻底丢失」的可能。只要不是无限重试，答案都是 true。 */
    public boolean mayLoseNotification() {
        return mode != NotifyMode.PULL && maxRetries >= 0;
    }
}
