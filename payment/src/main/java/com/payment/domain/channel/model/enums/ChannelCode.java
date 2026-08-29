package com.payment.domain.channel.model.enums;

/**
 * 支付通道编码枚举
 * 
 * 定义所有支持的支付通道
 */
public enum ChannelCode {
    
    // ==================== 国内通道 ====================
    
    /**
     * 微信JSAPI支付
     */
    WECHAT_JSAPI("WECHAT_JSAPI", "微信JSAPI支付", Region.DOMESTIC, true),
    
    /**
     * 微信Native支付
     */
    WECHAT_NATIVE("WECHAT_NATIVE", "微信Native支付", Region.DOMESTIC, true),
    
    /**
     * 微信H5支付
     */
    WECHAT_H5("WECHAT_H5", "微信H5支付", Region.DOMESTIC, true),
    
    /**
     * 微信APP支付
     */
    WECHAT_APP("WECHAT_APP", "微信APP支付", Region.DOMESTIC, true),
    
    /**
     * 微信小程序支付
     */
    WECHAT_MINI("WECHAT_MINI", "微信小程序支付", Region.DOMESTIC, true),
    
    /**
     * 支付宝电脑网站支付
     */
    ALIPAY_PC("ALIPAY_PC", "支付宝电脑网站支付", Region.DOMESTIC, true),
    
    /**
     * 支付宝手机网站支付
     */
    ALIPAY_WAP("ALIPAY_WAP", "支付宝手机网站支付", Region.DOMESTIC, true),
    
    /**
     * 支付宝APP支付
     */
    ALIPAY_APP("ALIPAY_APP", "支付宝APP支付", Region.DOMESTIC, true),
    
    /**
     * 支付宝当面付
     */
    ALIPAY_FACE_TO_FACE("ALIPAY_FACE_TO_FACE", "支付宝当面付", Region.DOMESTIC, true),
    
    /**
     * 京东网银支付
     */
    JDPAY_EBANK("JDPAY_EBANK", "京东网银支付", Region.DOMESTIC, true),
    
    /**
     * 京东快捷支付
     */
    JDPAY_QUICK("JDPAY_QUICK", "京东快捷支付", Region.DOMESTIC, true),
    
    // ==================== 国际通道 ====================
    
    /**
     * PayPal
     */
    PAYPAL("PAYPAL", "PayPal", Region.INTERNATIONAL, true),
    
    /**
     * Apple Pay
     */
    APPLE_PAY("APPLE_PAY", "Apple Pay", Region.INTERNATIONAL, true),
    
    /**
     * Stripe
     */
    STRIPE("STRIPE", "Stripe", Region.INTERNATIONAL, true),
    
    /**
     * Stripe Alipay (通过Stripe支持支付宝)
     */
    STRIPE_ALIPAY("STRIPE_ALIPAY", "Stripe Alipay", Region.INTERNATIONAL, true),
    
    /**
     * Stripe WeChat Pay (通过Stripe支持微信)
     */
    STRIPE_WECHAT("STRIPE_WECHAT", "Stripe WeChat Pay", Region.INTERNATIONAL, true),
    
    /**
     * Adyen (原Antom)
     */
    ADYEN("ADYEN", "Adyen", Region.INTERNATIONAL, true),
    
    /**
     * Worldpay
     */
    WORLDPAY("WORLDPAY", "Worldpay", Region.INTERNATIONAL, true),
    
    /**
     * 银联国际
     */
    UNIONPAY_INTL("UNIONPAY_INTL", "银联国际", Region.INTERNATIONAL, true);
    
    private final String code;
    private final String displayName;
    private final Region region;
    private final boolean enabled;
    
    ChannelCode(String code, String displayName, Region region, boolean enabled) {
        this.code = code;
        this.displayName = displayName;
        this.region = region;
        this.enabled = enabled;
    }
    
    public String getCode() {
        return code;
    }
    
    public String getDisplayName() {
        return displayName;
    }
    
    public Region getRegion() {
        return region;
    }
    
    public boolean isEnabled() {
        return enabled;
    }
    
    /**
     * 根据编码获取枚举
     */
    public static ChannelCode fromCode(String code) {
        for (ChannelCode channelCode : values()) {
            if (channelCode.code.equals(code)) {
                return channelCode;
            }
        }
        throw new IllegalArgumentException("不支持的支付通道: " + code);
    }
    
    /**
     * 区域枚举
     */
    public enum Region {
        /** 国内 */
        DOMESTIC,
        /** 国际 */
        INTERNATIONAL
    }
}
