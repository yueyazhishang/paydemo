package com.zxpay.domain.channel.model;

/**
 * 通道在支付产业链中的<b>角色分层</b>。
 *
 * <p>这是国内与海外通道最根本的差异，比「接口字段长什么样」重要得多。
 * 很多人把「微信支付 / 支付宝 / Stripe / PayPal / Apple Pay」平铺成一个列表，
 * 于是抽象出一个「万能接口」，最后被各家的差异撑爆。真实分层是：
 *
 * <pre>
 *   用户 ──▶ 钱包层(WALLET)      Apple Pay / Google Pay / 微信 / 支付宝
 *              │  产出 payment token（网络令牌）
 *              ▼
 *   PSP 层     Stripe / Antom / Braintree / Checkout.com
 *              │  统一 API + 内部再路由多家收单行 + 风控 + 3DS
 *              ▼
 *   收单层(ACQUIRER)  Worldpay / Adyen / 各银行
 *              │  把交易送进卡组织网络
 *              ▼
 *   卡组织(SCHEME)    Visa / Mastercard / UnionPay / JCB / Amex
 *                     定义 auth / capture / void / refund / chargeback 语义
 * </pre>
 *
 * <p><b>国内是「扁平」的</b>：微信支付、支付宝既是钱包、又是收单、又是清算角色，
 * 一个机构吃完全链路，所以只有一次集成、一次对账。
 *
 * <p><b>海外是「分层」的</b>：Apple Pay 只是钱包，它并不处理资金，
 * 必须挂在一个 PSP 或收单行下面（走 Stripe / Worldpay / Adyen 之一）。
 * 因此本 Demo 中 Apple Pay 不是一个「通道」，而是挂在 PSP 上的一种<b>支付方式</b>
 * （见 {@link PaymentMethod#APPLE_PAY}），真正的通道仍是 Stripe 或 Worldpay。
 * 这个认知如果搞反，整个领域模型就会错位。
 */
public enum ChannelCategory {

    /** 第三方支付（国内）：钱包 + 收单 + 清算一体。微信支付、支付宝、京东支付。 */
    TPP("第三方支付", true),

    /** 钱包：只提供支付工具与令牌，不处理资金，必须挂靠 PSP / 收单行。 */
    WALLET("钱包", false),

    /** 支付服务商：统一 API，内部聚合多家收单行、风控、3DS。Stripe / Antom。 */
    PSP("支付服务商", false),

    /** 收单机构：直接对接卡组织与银行。Worldpay / Adyen / 银行直连。 */
    ACQUIRER("收单机构", false),

    /** 卡组织：不直接对接商户，定义交易语义与清算规则。Visa / Mastercard / UnionPay。 */
    SCHEME("卡组织", false),

    /** 银行直连：网银、直连银行网关。 */
    BANK("银行", false),
    ;

    private final String displayName;

    /** 是否「钱包+收单+清算」一体化（国内第三方支付的独有特征）。 */
    private final boolean verticallyIntegrated;

    ChannelCategory(String displayName, boolean verticallyIntegrated) {
        this.displayName = displayName;
        this.verticallyIntegrated = verticallyIntegrated;
    }

    public String displayName() {
        return displayName;
    }

    /**
     * 一体化通道不需要再向下游路由，能力自足；
     * 非一体化通道（PSP / 收单）背后还藏着一层路由，故障时切换的粒度更细。
     */
    public boolean isVerticallyIntegrated() {
        return verticallyIntegrated;
    }
}
