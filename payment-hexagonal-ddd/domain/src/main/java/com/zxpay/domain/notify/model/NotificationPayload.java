package com.zxpay.domain.notify.model;

import com.zxpay.domain.channel.model.ChannelCode;
import com.zxpay.domain.payment.model.PaymentStatus;
import com.zxpay.sharedkernel.money.Money;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;

/**
 * 解析后的回调内容（归一化）。
 *
 * <p>{@code rawStatus} 与 {@code normalizedStatus} 双轨保留，
 * 理由见 {@code ChannelRawStatus}。
 *
 * <p>{@code eventTime} 用于<b>乱序通知的时序守卫</b>：
 * 通道重试或网络重放可能让旧通知后到。收到通知时，
 * 若其 {@code eventTime} 早于订单上已记录的状态变更时间，
 * 说明这是一条过期通知，应当丢弃而不是覆盖。
 * 缺少这个字段，就只能靠「先到先得」，在重放场景下会出错。
 */
public record NotificationPayload(
        ChannelCode channel,

        /** 通道侧订单号（对应我们下单时的 channelOrderNo）。 */
        String channelOrderNo,

        /** 通道侧交易号。已支付才有。 */
        String channelTransactionId,

        /** 通道原始状态字符串。 */
        String rawStatus,

        /** 归一化后的支付状态。 */
        PaymentStatus normalizedStatus,

        /** 实付金额。 */
        Money paidAmount,

        /** 支付完成时间。 */
        Instant paidAt,

        /** 通道侧事件时间。用于乱序判断，缺失时用接收时间兜底。 */
        Instant eventTime,

        /** 通道侧的额外字段，原样保留供排查使用。 */
        Map<String, String> extras
) {

    public NotificationPayload {
        extras = extras == null ? Map.of() : Collections.unmodifiableMap(extras);
    }

    /** 有效的事件时间：优先通道侧时间，缺失则用接收时间。 */
    public Instant effectiveEventTime(Instant receivedAt) {
        return eventTime != null ? eventTime : receivedAt;
    }
}
