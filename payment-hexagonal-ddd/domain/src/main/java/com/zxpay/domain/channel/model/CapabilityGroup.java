package com.zxpay.domain.channel.model;

/**
 * 能力分组。仅用于文档化与配置归类，不参与路由判定。
 */
public enum CapabilityGroup {

    /** 交易模型：授权与请款是否分离。国内外最大的语义差异就在这里。 */
    TRADE_MODEL("交易模型"),

    /** 下单形态：前端如何被唤起。 */
    INVOCATION("下单形态"),

    /** 安全与鉴权：签名、证书、3DS、令牌化。 */
    SECURITY("安全鉴权"),

    /** 退款能力。 */
    REFUND("退款"),

    /** 订单管理：查单、关单、撤销。 */
    ORDER_OPS("订单管理"),

    /** 异步通知特性。 */
    NOTIFY("异步通知"),

    /** 币种能力。 */
    CURRENCY("币种"),

    /** 争议与拒付。 */
    DISPUTE("争议处理"),

    /** 增值能力：分账、订阅、担保。 */
    VALUE_ADDED("增值能力"),
    ;

    private final String displayName;

    CapabilityGroup(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
