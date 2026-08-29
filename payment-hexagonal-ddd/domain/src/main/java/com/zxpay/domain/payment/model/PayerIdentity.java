package com.zxpay.domain.payment.model;

import java.util.Map;
import java.util.Optional;

/**
 * 用户在通道侧的身份标识。
 *
 * <p>各通道叫法与获取方式完全不同：
 * <ul>
 *   <li>微信 JSAPI：<b>openid</b>，通过网页授权 OAuth 获取，同一用户在同一公众号下唯一。</li>
 *   <li>微信小程序：<b>openid</b>，通过 {@code code2Session} 换取。</li>
 *   <li>支付宝：<b>buyer_id</b>（用户唯一号），通过 {@code alipay.system.oauth.token} 换取。</li>
 *   <li>Stripe：<b>customer</b>，由我们创建并保管，可跨次复用。</li>
 *   <li>PayPal Vault：<b>payer_id</b>，用户首次授权后返回。</li>
 *   <li>Apple Pay：不直接给我们身份，只给一个 <b>payment token</b>（网络令牌），
 *       且该 token 只能用一次。</li>
 * </ul>
 *
 * <p>关键差异：国内的身份标识是「用户在通道生态内的账号」，相对持久；
 * 海外的 payment token 是「一次性的支付凭证」，用完即废，不能用于下次扣款
 * （要做订阅必须走 Vault / 网络令牌体系）。这个差异直接决定了
 * 「续费扣款」在两个市场上是完全不同的实现。
 */
public record PayerIdentity(
        /** 身份类型，如 openid / buyer_id / customer / payer_id / payment_token。 */
        String type,

        /** 身份值。 */
        String value
) {

    public PayerIdentity {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("payer identity type must not be blank");
        }
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("payer identity value must not be blank");
        }
    }

    public static PayerIdentity of(String type, String value) {
        return new PayerIdentity(type, value);
    }

    public static PayerIdentity wechatOpenid(String openid) {
        return new PayerIdentity("openid", openid);
    }

    public static PayerIdentity alipayBuyerId(String buyerId) {
        return new PayerIdentity("buyer_id", buyerId);
    }

    public static PayerIdentity stripeCustomer(String customerId) {
        return new PayerIdentity("customer", customerId);
    }

    public static PayerIdentity applePaymentToken(String token) {
        return new PayerIdentity("payment_token", token);
    }

    /** 是否为一次性凭证（用完即废，不可用于下次扣款）。 */
    public boolean isSingleUse() {
        return "payment_token".equals(type);
    }

    public Map<String, String> asMap() {
        return Map.of("type", type, "value", value);
    }

    public static Optional<PayerIdentity> empty() {
        return Optional.empty();
    }
}
