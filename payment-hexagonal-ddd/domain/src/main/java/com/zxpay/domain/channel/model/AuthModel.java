package com.zxpay.domain.channel.model;

/**
 * 通道鉴权模型。
 *
 * <p>各通道差异巨大，但抽象后只有这几类。适配器必须把「拿到一个已认证的请求」
 * 封装在基础设施层，领域层只见 {@code ChannelPaymentPort}，绝不能见到证书或密钥。
 *
 * <ul>
 *   <li><b>MERCHANT_CERT</b>：微信支付 APIv3。商户 API 证书私钥签名 + 平台证书加密敏感字段，
 *       且敏感信息（如退款通知里的用户姓名）需 AEAD 解密。</li>
 *   <li><b>RSA2_KEY_PAIR</b>：支付宝。应用私钥签名（RSA2/SHA256），支付宝公钥验签。</li>
 *   <li><b>API_KEY</b>：Stripe。Bearer {@code sk_live_xxx}，密钥即身份，泄露等于资金敞口。</li>
 *   <li><b>OAUTH2_CLIENT</b>：PayPal / Worldpay。先用 client_id+secret 换 access_token，
 *       带过期时间，需要缓存与自动刷新——这是最容易做错的一类（并发下 token 刷新风暴）。</li>
 *   <li><b>MUTUAL_TLS</b>：部分欧洲收单机构要求双向 TLS。</li>
 * </ul>
 */
public enum AuthModel {

    MERCHANT_CERT("商户证书", true, false),
    RSA2_KEY_PAIR("RSA公私钥", true, false),
    API_KEY("API Key", false, false),
    OAUTH2_CLIENT("OAuth2 客户端凭证", false, true),
    MUTUAL_TLS("双向TLS", false, false),
    ;

    private final String displayName;

    /** 请求是否需要逐笔签名（是则每笔都有 CPU 开销，且要做签名串拼接的严格排序）。 */
    private final boolean perRequestSignature;

    /** 凭据是否带过期时间（是则需要缓存 + 并发刷新保护，否则会打爆授权服务）。 */
    private final boolean expiringCredential;

    AuthModel(String displayName, boolean perRequestSignature, boolean expiringCredential) {
        this.displayName = displayName;
        this.perRequestSignature = perRequestSignature;
        this.expiringCredential = expiringCredential;
    }

    public String displayName() {
        return displayName;
    }

    public boolean isPerRequestSignature() {
        return perRequestSignature;
    }

    public boolean isExpiringCredential() {
        return expiringCredential;
    }
}
