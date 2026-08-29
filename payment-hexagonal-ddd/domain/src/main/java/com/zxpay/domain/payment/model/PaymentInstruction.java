package com.zxpay.domain.payment.model;

import com.zxpay.domain.channel.model.InteractionMode;
import com.zxpay.domain.channel.model.PaymentMethod;
import com.zxpay.sharedkernel.money.Money;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;

/**
 * 支付指令：<b>通道无关</b>的「这笔交易要做什么」。
 *
 * <p>创建后不可变——它是商户意图的快照。即便后续商户配置变了
 * （换了回调地址、改了支持的支付方式），已创建的支付单仍按创建时的意图执行。
 * 这条性质避免了「支付过程中配置变更导致行为漂移」这类极难排查的问题。
 *
 * <p>与 {@code ChannelRequest} 的区别：
 * <ul>
 *   <li>{@code PaymentInstruction} = 商户的<b>业务意图</b>，属于支付上下文，持久化在支付单上。</li>
 *   <li>{@code ChannelRequest} = 面向某家通道的<b>技术请求</b>，
 *       由指令 + 路由结果 + 通道签约信息在每次尝试时组装而成。</li>
 * </ul>
 * 一次支付单可以产生多个 ChannelRequest（重试或换通道），但指令始终只有一份。
 */
public record PaymentInstruction(
        PaymentMethod paymentMethod,

        /** 期望的交互形态。由终端类型与支付方式共同决定。 */
        InteractionMode interactionMode,

        Money amount,

        /** 请款模式。MANUAL 时通道只做授权。 */
        CaptureMode captureMode,

        /** 订单有效期。超时后不再受理支付。 */
        Duration expiry,

        /** 商品标题，展示在通道收银台上。 */
        String subject,

        PaymentScene scene,

        /** 用户在通道侧的身份。JSAPI / 小程序 / Vault 扣款必需。 */
        PayerIdentity payerIdentity,

        /** 支付结果回调地址（我方接收后转发给商户）。 */
        String notifyUrl,

        /** 支付完成后的前端跳转地址。 */
        String returnUrl,

        /** 商户自定义透传字段，回调时原样带回。 */
        Map<String, String> metadata
) {

    /** 默认有效期。微信预支付订单默认 2 小时，此处取行业常见值。 */
    public static final Duration DEFAULT_EXPIRY = Duration.ofHours(2);

    public PaymentInstruction {
        if (paymentMethod == null) {
            throw new IllegalArgumentException("paymentMethod must not be null");
        }
        if (amount == null || !amount.isPositive()) {
            throw new IllegalArgumentException("amount must be positive: " + amount);
        }
        if (captureMode == null) {
            captureMode = CaptureMode.AUTOMATIC;
        }
        if (interactionMode == null) {
            interactionMode = paymentMethod.interactionMode();
        }
        if (expiry == null) {
            expiry = DEFAULT_EXPIRY;
        }
        metadata = metadata == null ? Map.of() : Collections.unmodifiableMap(metadata);
    }

    public static PaymentInstruction of(PaymentMethod method, Money amount, PaymentScene scene) {
        return new PaymentInstruction(method, null, amount, CaptureMode.AUTOMATIC,
                DEFAULT_EXPIRY, null, scene, null, null, null, null);
    }
}
