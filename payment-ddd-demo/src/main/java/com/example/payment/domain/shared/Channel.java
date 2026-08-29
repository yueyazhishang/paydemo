package com.example.payment.domain.shared;

import lombok.Getter;

/**
 * 支付渠道（通用语言中的「渠道」概念）。
 * 渠道的一切私有语义（签名、报文、金额单位）不允许渗透到领域层，由防腐层翻译。
 */
@Getter
public enum Channel {

    // 国内
    WECHAT_PAY("微信支付", Region.CN),
    ALIPAY("支付宝", Region.CN),
    JD_PAY("京东支付", Region.CN),

    // 国外
    PAYPAL("PayPal", Region.OVERSEAS),
    APPLE_PAY("Apple Pay", Region.OVERSEAS),
    ANTOM("Antom(支付宝国际)", Region.OVERSEAS),
    WORLDPAY("Worldpay", Region.OVERSEAS),
    STRIPE("Stripe", Region.OVERSEAS);

    public enum Region { CN, OVERSEAS }

    private final String displayName;
    private final Region region;

    Channel(String displayName, Region region) {
        this.displayName = displayName;
        this.region = region;
    }
}
