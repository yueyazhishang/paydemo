package com.zxpay.domain.channel.model;

/**
 * 支付方式：<b>用户视角</b>的付款手段。
 *
 * <p>必须和 {@link ChannelCode}（机构视角）严格区分——这是支付建模的第一道分水岭：
 * <ul>
 *   <li>「用微信付款」是支付方式，「走微信支付这个机构」是通道。</li>
 *   <li>一个通道支持多种支付方式（微信支付支持 JSAPI/APP/H5/NATIVE/付款码）。</li>
 *   <li>一种支付方式可落到多个通道（Apple Pay 可走 Stripe，也可走 Worldpay）。</li>
 * </ul>
 * 把两者混为一谈，就会出现 {@code switch(channel)} 里再嵌 {@code switch(method)} 的双层地狱。
 *
 * <p>{@code payerIdentityRequired} 表示是否必须先拿到用户在通道侧的身份标识：
 * 微信 JSAPI 需要 openid，支付宝需要 buyer_id，PayPal Vault 需要 payer_id。
 * 缺少它下单必然失败，因此路由前就要校验，而不是等通道报错。
 */
public enum PaymentMethod {

    // ---------- 微信 ----------
    WECHAT_JSAPI("微信JSAPI", InteractionMode.FRONTEND_SDK, true),
    WECHAT_MINI("微信小程序", InteractionMode.FRONTEND_SDK, true),
    WECHAT_APP("微信APP", InteractionMode.FRONTEND_SDK, false),
    WECHAT_H5("微信H5", InteractionMode.REDIRECT, false),
    WECHAT_NATIVE("微信扫码", InteractionMode.SCAN_QR, false),
    WECHAT_MICRO("微信付款码", InteractionMode.BARCODE, false),

    // ---------- 支付宝 ----------
    ALIPAY_WAP("支付宝手机网站", InteractionMode.REDIRECT, false),
    ALIPAY_PAGE("支付宝电脑网站", InteractionMode.REDIRECT, false),
    ALIPAY_APP("支付宝APP", InteractionMode.FRONTEND_SDK, false),
    ALIPAY_F2F("支付宝当面付", InteractionMode.SCAN_QR, false),
    ALIPAY_FACE("支付宝刷脸", InteractionMode.BARCODE, true),

    // ---------- 京东 / 银联 ----------
    JD_APP("京东APP", InteractionMode.FRONTEND_SDK, false),
    JD_H5("京东H5", InteractionMode.REDIRECT, false),
    JD_QR("京东扫码", InteractionMode.SCAN_QR, false),
    UNIONPAY_CLOUD_QUICKPASS("云闪付", InteractionMode.FRONTEND_SDK, false),
    UNIONPAY_GATEWAY("银联网关", InteractionMode.REDIRECT, false),

    // ---------- 海外卡与钱包 ----------
    CARD("银行卡", InteractionMode.API_ONLY, false),
    APPLE_PAY("Apple Pay", InteractionMode.FRONTEND_SDK, false),
    GOOGLE_PAY("Google Pay", InteractionMode.FRONTEND_SDK, false),

    // ---------- PayPal 体系 ----------
    PAYPAL_WALLET("PayPal钱包", InteractionMode.REDIRECT, false),
    PAYPAL_VAULT("PayPal代扣", InteractionMode.API_ONLY, true),

    // ---------- 本地化支付（海外常见，国内几乎不存在） ----------
    BANK_TRANSFER("银行转账", InteractionMode.ASYNC_INSTRUCTION, false),
    SEPA_DEBIT("SEPA借记", InteractionMode.ASYNC_INSTRUCTION, false),
    ;

    private final String displayName;
    private final InteractionMode interactionMode;

    /** 是否需要先获取用户在通道侧的身份标识（openid / buyer_id / customer id）。 */
    private final boolean payerIdentityRequired;

    PaymentMethod(String displayName, InteractionMode interactionMode, boolean payerIdentityRequired) {
        this.displayName = displayName;
        this.interactionMode = interactionMode;
        this.payerIdentityRequired = payerIdentityRequired;
    }

    public String displayName() {
        return displayName;
    }

    public InteractionMode interactionMode() {
        return interactionMode;
    }

    public boolean isPayerIdentityRequired() {
        return payerIdentityRequired;
    }
}
