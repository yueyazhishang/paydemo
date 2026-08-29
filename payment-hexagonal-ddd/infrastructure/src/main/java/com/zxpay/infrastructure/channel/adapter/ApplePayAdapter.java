package com.zxpay.infrastructure.channel.adapter;

import com.zxpay.domain.channel.model.ChannelCode;
import com.zxpay.domain.notify.model.NotificationEnvelope;
import com.zxpay.domain.notify.model.NotificationPayload;
import com.zxpay.domain.notify.port.ChannelNotifyParser;
import com.zxpay.domain.notify.port.ChannelNotifyVerifier;
import com.zxpay.domain.payment.model.PaymentStatus;

import java.util.Optional;

/**
 * Apple Pay 适配器——<b>它刻意不实现任何收单端口</b>。
 *
 * <p>这是整个 Demo 里最能说明「角色分层」重要性的一处设计。
 *
 * <p>很多团队的通道列表长这样：
 * {@code [微信, 支付宝, 京东, 银联, Stripe, PayPal, Apple Pay, Worldpay]}——
 * 把 Apple Pay 和微信、Stripe 并列当成同一类东西。这是错的：
 *
 * <pre>
 *   微信/支付宝 = 钱包 + 收单 + 清算（一体化，一家吃完全链路）
 *   Stripe/Antom = PSP（统一 API，内部再路由收单行）
 *   Worldpay = 收单机构（直接对接卡组织）
 *   银联/Visa = 卡组织（定义交易语义，不直接对商户）
 *   Apple Pay = 钱包（<b>只产出支付凭证，不处理资金</b>）
 * </pre>
 *
 * <p>Apple Pay 做的事是：把用户的银行卡信息做<b>网络令牌化</b>（DPAN），
 * 产出一个一次性 {@code payment token} 交给商户。
 * <b>它既不授权、也不请款、更不结算</b>——这些必须由下游的
 * Stripe / Worldpay / Antom 之一完成。
 *
 * <p>因此在本模型中：
 * <ul>
 *   <li>{@code PaymentMethod.APPLE_PAY} 是支付方式，用户视角的「用 Apple Pay 付款」。</li>
 *   <li>{@code ChannelCode.APPLE_PAY} 的 category 是 WALLET，
 *       {@code isAcquirable()} 为 false，<b>会被能力矩阵排除在路由之外</b>。</li>
 *   <li>真正的下单通道仍是 PSP，Apple Pay 只是它们支持的其中一种支付方式。</li>
 * </ul>
 *
 * <p>如果建模搞反了（把 Apple Pay 当通道），会出现：
 * 直接向 Apple 发起下单请求 → 无此接口；
 * 无法处理 3DS（3DS 是下游 PSP 的职责）；
 * 无法做退款（退款要找 PSP）。
 *
 * <p>本类只实现回调相关的能力，且实际上 Apple Pay 的通知也是由下游 PSP 转发的。
 */
public class ApplePayAdapter implements ChannelNotifyVerifier, ChannelNotifyParser {

    /**
     * 通道标识。
     *
     * <p>注意这里<b>没有 {@code @Override}</b>——Apple Pay 适配器不实现
     * {@code ChannelPort}，因此它没有 {@code channel()} 这个契约方法。
     * 本方法只是为了方便注册表索引而自行提供。
     */
    public ChannelCode channel() {
        return ChannelCode.APPLE_PAY;
    }

    /**
     * Apple Pay 的验签本质是<b>校验支付凭证本身的签名</b>，
     * 用 Apple 的根证书验证 payment token 的 PKCS#7 签名，
     * 确认这个 token 确实由 Apple 签发且未被篡改。
     *
     * <p>但它不校验业务状态——业务状态由下游 PSP 的通知决定。
     */
    @Override
    public VerifyOutcome verify(NotificationEnvelope envelope) {
        if (envelope.rawBody() == null || envelope.rawBody().isBlank()) {
            return VerifyOutcome.invalid("empty payment token");
        }
        // 真实实现：用 Apple Root CA 验证 PKCS#7 签名，并校验 token 中的
        // merchantIdentifier 与本次交易的商户匹配（防止 A 商户的 token 被用于 B 商户）
        return VerifyOutcome.verified();
    }

    @Override
    public Optional<NotificationPayload> parse(NotificationEnvelope envelope) {
        // Apple Pay 本身不推送业务状态；状态一律来自下游 PSP。
        // 这里返回 UNKNOWN 归一化状态，交由上层交由 PSP 通知覆盖。
        return Optional.of(new NotificationPayload(
                channel(), null, null, "TOKEN_ONLY",
                PaymentStatus.PAYING, null, null, envelope.receivedAt(),
                java.util.Map.of("note", "Apple Pay 只提供支付凭证，业务状态需由下游 PSP 通知确定")));
    }
}
