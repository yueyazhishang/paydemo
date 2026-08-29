package com.zxpay.domain.payment.model;

import com.zxpay.domain.channel.model.ChannelCode;
import com.zxpay.domain.channel.model.InteractionMode;
import com.zxpay.domain.channel.model.PaymentMethod;
import com.zxpay.sharedkernel.money.Money;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;

/**
 * 下发通道的下单请求：<b>通道无关</b>的领域指令。
 *
 * <p>领域层只构造这个对象，不知道微信要 {@code out_trade_no} 还是 Stripe 要
 * {@code Idempotency-Key}。翻译工作全部由基础设施层的适配器完成。
 *
 * <p>两个幂等字段并存，正是为了覆盖两类通道：
 * <ul>
 *   <li>{@code merchantOrderNo}：国内通道以它为主键做幂等，必须业务唯一。</li>
 *   <li>{@code idempotencyKey}：Stripe / PayPal 的请求头幂等键。
 *       <b>必须由领域层生成并随尝试持久化</b>（见 {@code PaymentAttempt}），
 *       否则重试时重新生成一个新 key，通道会当成一笔全新的交易——
 *       这就是「重试导致重复扣款」的根因。</li>
 * </ul>
 */
public record ChannelRequest(
        PaymentAttemptId attemptId,
        PaymentOrderId orderId,
        ChannelCode channel,

        /** 通道侧幂等键。重试必须复用同一个值。 */
        String idempotencyKey,

        /** 商户订单号。国内通道以此做业务幂等。 */
        String merchantOrderNo,

        /**
         * 实际下发给通道的订单号。
         *
         * <p>首次尝试时等于 {@code merchantOrderNo}；
         * 切换通道后加序号后缀（{@code ORDER_NO-2}），
         * 避免不同通道间出现相同单号造成的对账歧义。
         *
         * <p>注意与 {@code merchantOrderNo} 区分：
         * 前者是<b>本次尝试发给通道的</b>号，后者是<b>商户业务系统的</b>号。
         * 回调定位、对账、客服查询都以前者为准，业务幂等以后者为准。
         */
        String channelOrderNo,

        Money amount,
        PaymentMethod paymentMethod,
        InteractionMode interactionMode,

        /** 用户身份。Apple Pay 等场景下为一次性 token。 */
        PayerIdentity payerIdentity,

        String subject,
        PaymentScene scene,

        /** 订单失效时间。超时后通道不再受理。 */
        Instant expireAt,

        CaptureMode captureMode,

        /** 通道回调地址。部分通道（Stripe）用 webhook 全局配置而非逐笔传入。 */
        String notifyUrl,

        /** 支付完成后跳转回商户的地址（REDIRECT 模式）。 */
        String returnUrl,

        Map<String, String> metadata
) {

    public ChannelRequest {
        metadata = metadata == null ? Map.of() : Collections.unmodifiableMap(metadata);
    }

    /** 是否要求「先授权不请款」。 */
    public boolean requiresAuthorizationOnly() {
        return captureMode != null && captureMode.requiresExplicitCapture();
    }
}
