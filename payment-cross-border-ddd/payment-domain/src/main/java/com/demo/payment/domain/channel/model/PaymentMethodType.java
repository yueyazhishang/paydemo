package com.demo.payment.domain.channel.model;

/**
 * 支付方式 —— 与 {@link ChannelCode} 正交的第二个维度。
 *
 * <p>用户的"支付意图"是这一层，而不是通道。用户说"我要用 Apple Pay"，
 * 他并不关心背后是 Stripe 还是 Worldpay 收单 —— 那是路由的职责。
 *
 * <p><b>为什么要单独建模：</b>收银台渲染、风控规则、费率计算、对账口径
 * 全部是按支付方式走，而非按通道。若把二者合并，收银台上"Apple Pay"这个按钮
 * 就会跟死在某一个通道上，通道一挂，按钮就得下线，没法做容灾切换。
 */
public enum PaymentMethodType {

    // ---------- 钱包 ----------
    WECHAT_PAY("微信支付"),
    ALIPAY_WALLET("支付宝"),
    PAYPAL_WALLET("PayPal 钱包"),
    JD_PAY("京东支付"),

    // ---------- 银行卡 ----------
    BANK_CARD("银行卡"),
    UNION_PAY_CARD("银联卡"),

    // ---------- 钱包/凭证网络（不直接扣款，需委托收单行） ----------
    /** Apple Pay：产出 PKPaymentToken，必须由卡收单通道解密执行 */
    APPLE_PAY("Apple Pay"),
    GOOGLE_PAY("Google Pay"),

    // ---------- 替代支付方式（APM） ----------
    /** 先买后付，如 Klarna / Kredivo / BillEase / Tamara */
    BNPL("先买后付"),
    /** 网银转账，如德国 Sofort、荷兰 iDEAL */
    ONLINE_BANKING("网银转账"),
    /** 现金支付，如巴西 Boleto、墨西哥 OXXO */
    CASH("现金支付"),
    /** 实时转账，如巴西 PIX、印度 UPI */
    REAL_TIME_PAYMENT("实时转账"),

    ;

    private final String displayName;

    PaymentMethodType(String displayName) { this.displayName = displayName; }

    public String displayName() { return displayName; }

    /** 该支付方式是否为"凭证网络"类，需要委托给真正的收单通道执行 */
    public boolean isCredentialNetwork() {
        return this == APPLE_PAY || this == GOOGLE_PAY;
    }
}
