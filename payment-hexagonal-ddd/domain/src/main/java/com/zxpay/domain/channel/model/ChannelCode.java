package com.zxpay.domain.channel.model;

import java.util.Locale;

/**
 * 通道编码：一家我们实际对接的机构。
 *
 * <p>注意枚举成员的角色并不对等（详见 {@link ChannelCategory}）：
 * <ul>
 *   <li>{@link #WECHAT_PAY} / {@link #ALIPAY} / {@link #JD_PAY} / {@link #UNIONPAY}
 *       是国内一体化第三方支付，直接面向商户收单。</li>
 *   <li>{@link #STRIPE} / {@link #ANTOM} 是 PSP，提供统一 API 并内部路由收单行。</li>
 *   <li>{@link #PAYPAL} 兼具钱包与 PSP 属性：既有用户余额账户，也处理卡收单
 *       （Braintree 卡业务）。</li>
 *   <li>{@link #WORLDPAY} 是老牌收单机构/网关，直接对接卡组织。</li>
 *   <li>{@link #APPLE_PAY} <b>不是通道</b>，是挂在 PSP/收单行下的支付方式。
 *       此处保留条目仅为演示「能力矩阵会拒绝把 Apple Pay 当通道直接下单」。</li>
 * </ul>
 *
 * <p>生产环境通道通常是配置化的（DB / 配置中心），此处用枚举是为了教学可读：
 * 枚举强制你在编译期就面对「新增通道需要声明哪些能力」。
 */
public enum ChannelCode {

    WECHAT_PAY("微信支付", ChannelCategory.TPP, "CN"),
    ALIPAY("支付宝", ChannelCategory.TPP, "CN"),
    JD_PAY("京东支付", ChannelCategory.TPP, "CN"),
    UNIONPAY("银联", ChannelCategory.SCHEME, "CN"),

    STRIPE("Stripe", ChannelCategory.PSP, "US"),
    PAYPAL("PayPal", ChannelCategory.PSP, "US"),
    ANTOM("Antom", ChannelCategory.PSP, "SG"),
    WORLDPAY("Worldpay", ChannelCategory.ACQUIRER, "GB"),

    /** 钱包，非通道。仅用于演示能力校验会将其排除。 */
    APPLE_PAY("Apple Pay", ChannelCategory.WALLET, "US"),
    ;

    private final String displayName;
    private final ChannelCategory category;
    private final String homeCountry;

    ChannelCode(String displayName, ChannelCategory category, String homeCountry) {
        this.displayName = displayName;
        this.category = category;
        this.homeCountry = homeCountry;
    }

    public String displayName() {
        return displayName;
    }

    public ChannelCategory category() {
        return category;
    }

    public String homeCountry() {
        return homeCountry;
    }

    /** 是否可直接作为收单通道使用（钱包不可）。 */
    public boolean isAcquirable() {
        return category != ChannelCategory.WALLET && category != ChannelCategory.SCHEME;
    }

    /** 国内通道（人民币、境内主体、受国内清算体系约束）。 */
    public boolean isDomestic() {
        return "CN".equals(homeCountry);
    }

    public static ChannelCode of(String code) {
        return ChannelCode.valueOf(code.trim().toUpperCase(Locale.ROOT));
    }
}
