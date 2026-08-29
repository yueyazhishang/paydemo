package com.zxpay.domain.payment.model;

import java.time.Instant;

/**
 * 通道原始状态 + 归一化映射，双轨保留。
 *
 * <p>归一化是必要的（上层不该知道九套状态名），但<b>归一化是有损压缩</b>，
 * 因此必须把原始状态一并存下来：
 * <ul>
 *   <li>微信 {@code USERPAYING}（用户支付中）→ 归一化 {@code USERPAYING}，语义刚好对齐。</li>
 *   <li>微信 {@code PAYERROR}（支付失败，含被判盗刷）→ 归一化 {@code FAILED}，
 *       但原始码能区分「余额不足」和「风控拦截」，运营处理截然不同。</li>
 *   <li>支付宝 {@code TRADE_FINISHED}（不可退款）vs {@code TRADE_SUCCESS}（可退款）
 *       ——这两者在归一化后都是 {@code SUCCEEDED}，<b>但退款可行性完全不同</b>。
 *       丢了原始状态，就会对一笔已完成的交易发起注定失败的退款。</li>
 *   <li>Stripe {@code requires_action}（需 3DS 挑战）→ 归一化 {@code PAYING}，
 *       但业务上必须引导用户去完成挑战，否则订单会一直挂着。</li>
 * </ul>
 */
public record ChannelRawStatus(
        /** 通道原始状态字符串，原样保存。 */
        String rawStatus,

        /** 归一化后的平台状态。 */
        PaymentStatus normalized,

        /** 该状态在通道侧的语义说明，供运营与客服查阅。 */
        String description,

        /** 通道侧记录的状态发生时间。用于乱序通知的时序判断。 */
        Instant occurredAt
) {

    public ChannelRawStatus {
        if (normalized == null) {
            throw new IllegalArgumentException("normalized status must not be null");
        }
    }

    public static ChannelRawStatus of(String rawStatus, PaymentStatus normalized, String description) {
        return new ChannelRawStatus(rawStatus, normalized, description, null);
    }

    public static ChannelRawStatus of(String rawStatus, PaymentStatus normalized, String description, Instant at) {
        return new ChannelRawStatus(rawStatus, normalized, description, at);
    }
}
