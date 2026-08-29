package com.demo.payment.domain.channel.model;

/**
 * 通道编码（PSP / 收单机构）。
 *
 * <p><b>关键认知：通道 ≠ 支付方式。</b>
 * 这是很多人做支付抽象时踩的第一个坑。举三个例子说明为什么不能合并：
 * <ul>
 *   <li><b>Apple Pay</b> 不是通道，是凭证网络。它产出加密的 {@code PKPaymentToken}，
 *       必须经由 Stripe / Worldpay / Adyen 这类收单行解密并请款。
 *       所以它<b>不在本枚举里作为可直连通道</b>，而是 {@link PaymentMethodType#APPLE_PAY}，
 *       运行时由 {@link ChannelCode#STRIPE} 等卡收单通道承载。</li>
 *   <li><b>Antom</b> 是聚合收单平台，一个通道枚举背后挂着 300+ 支付方式
 *       （卡、钱包、BNPL、网银、现金支付）。它是"通道里的通道"。</li>
 *   <li><b>支付宝收单</b> 既能承载 {@code balance}（余额）也能承载 {@code bankCard}（快捷卡），
 *       一个通道对多种支付方式。</li>
 * </ul>
 *
 * <p>因此正确的模型是二维的：
 * <pre>
 *     支付方式(PaymentMethodType)  ×  通道(ChannelCode)  →  能力矩阵(ChannelCapability)
 * </pre>
 * 路由决策 = 先按支付方式筛出可用通道，再按费率/成功率/限额/灰度排序。
 */
public enum ChannelCode {

    // ---------- 国内 ----------

    /** 微信支付 v3（JSAPI / Native / APP / H5 / 小程序） */
    WECHAT_PAY("wechatpay", "微信支付", Region.DOMESTIC),

    /** 支付宝（当面付 / 手机网站 / APP / PC） */
    ALIPAY("alipay", "支付宝", Region.DOMESTIC),

    /** 京东支付（原网银在线，网关型，聚合银行卡 + 京东白条） */
    JD_PAY("jdpay", "京东支付", Region.DOMESTIC),

    /** 银联（全渠道 / 云闪付） */
    UNION_PAY("unionpay", "银联", Region.DOMESTIC),

    // ---------- 海外 ----------

    /** PayPal（Orders v2 授权/捕获 + 钱包） */
    PAYPAL("paypal", "PayPal", Region.GLOBAL),

    /** Stripe（PaymentIntent 状态机 + 卡收单） */
    STRIPE("stripe", "Stripe", Region.GLOBAL),

    /** Worldpay（XML paymentService v1.4，老牌卡收单，支持 Apple Pay 直连） */
    WORLDPAY("worldpay", "Worldpay", Region.GLOBAL),

    /** Antom（蚂蚁国际，聚合 300+ 支付方式） */
    ANTOM("antom", "Antom", Region.GLOBAL),

    ;

    private final String code;
    private final String displayName;
    private final Region region;

    ChannelCode(String code, String displayName, Region region) {
        this.code = code;
        this.displayName = displayName;
        this.region = region;
    }

    public String code() { return code; }
    public String displayName() { return displayName; }
    public Region region() { return region; }
    public boolean isDomestic() { return region == Region.DOMESTIC; }
    public boolean isGlobal() { return region == Region.GLOBAL; }

    public enum Region { DOMESTIC, GLOBAL }
}
