package com.zxpay.domain.channel.model;

/**
 * 通道能力位。
 *
 * <p>设计意图：把「通道差异」从<b>代码分支</b>变成<b>可查询的数据</b>。
 *
 * <p>传统写法是 {@code if (channel == WECHAT) {...} else if (channel == STRIPE) {...}}，
 * 每接一家新通道就要在 N 处改代码。能力位方案下，接新通道只需在
 * {@link ChannelCapability} 配置里声明它<b>能做什么</b>、<b>不能做什么</b>，
 * 业务代码全程只问 {@code capability.supports(XXX)}。
 *
 * <h3>国内外差异的几个关键位</h3>
 * <ul>
 *   <li>{@link #SALE} vs {@link #AUTH_ONLY}：国内以「即时交易」为主，下单即扣款；
 *       海外卡组标准是「先授权冻结额度，商户发货后再请款」。这是语义层面的分歧，
 *       处理方式见 {@code CaptureMode}。</li>
 *   <li>{@link #REVERSE}：微信/支付宝支持「支付后撤销」，把当天交易作废并按原路退回，
 *       卡组世界里对应的其实是 VOID（撤销未请款的授权）或 REFUND（已请款后退款）。
 *       语义不同，映射时不能想当然对齐。</li>
 *   <li>{@link #THREE_DS_CHALLENGE}：海外强监管（SCA/PSD2）下卡支付常需 3DS 挑战，
 *       下单后要用户做二次验证；国内没有对应环节。</li>
 *   <li>{@link #DISPUTE} / {@link #CHARGEBACK_REPRESENTMENT}：海外有完整的拒付与申诉流程，
 *       国内对应的是「投诉」与平台介入，二者流程与资金流向完全不同。</li>
 *   <li>{@link #ESCROW}：国内担保交易成熟（确认收货才结算给商家），
 *       海外卡体系里没有等价物，只能靠 auth + delayed capture 近似模拟。</li>
 * </ul>
 */
public enum Capability {

    // ---------- 交易模型 ----------
    /** 即时交易：授权与请款一步完成（Sale）。国内主流。 */
    SALE(CapabilityGroup.TRADE_MODEL, "即时交易"),

    /** 仅授权：冻结用户额度，不实际扣款。卡组标准。 */
    AUTH_ONLY(CapabilityGroup.TRADE_MODEL, "仅授权"),

    /** 请款：对已授权金额实际扣款。 */
    CAPTURE(CapabilityGroup.TRADE_MODEL, "请款"),

    /** 部分请款：可只请款授权金额的一部分（常见于预售、缺货）。 */
    PARTIAL_CAPTURE(CapabilityGroup.TRADE_MODEL, "部分请款"),

    /** 增量授权：授权后追加额度（酒店/租车押金场景）。 */
    INCREMENTAL_AUTH(CapabilityGroup.TRADE_MODEL, "增量授权"),

    /** 撤销授权：解冻未请款的冻结额度。 */
    VOID(CapabilityGroup.TRADE_MODEL, "撤销授权"),

    // ---------- 下单形态 ----------
    FRONTEND_SDK_INVOKE(CapabilityGroup.INVOCATION, "前端SDK唤起"),
    QR_PRECREATE(CapabilityGroup.INVOCATION, "预生成二维码"),
    HOSTED_REDIRECT(CapabilityGroup.INVOCATION, "托管页跳转"),
    BARCODE_DIRECT(CapabilityGroup.INVOCATION, "条码直扣"),
    SERVER_TO_SERVER(CapabilityGroup.INVOCATION, "纯服务端下单"),

    // ---------- 安全鉴权 ----------
    THREE_DS_CHALLENGE(CapabilityGroup.SECURITY, "3DS挑战"),
    NETWORK_TOKENIZATION(CapabilityGroup.SECURITY, "网络令牌化"),
    CERT_BASED_SIGN(CapabilityGroup.SECURITY, "商户证书签名"),
    ASYM_KEY_SIGN(CapabilityGroup.SECURITY, "RSA公私钥签名"),
    WEBHOOK_SIGNATURE(CapabilityGroup.SECURITY, "回调签名"),

    // ---------- 退款 ----------
    FULL_REFUND(CapabilityGroup.REFUND, "全额退款"),
    PARTIAL_REFUND(CapabilityGroup.REFUND, "部分退款"),
    MULTIPLE_PARTIAL_REFUND(CapabilityGroup.REFUND, "多次部分退款"),

    /** 退款同步返回结果，无需等待异步通知。卡组退款通常是异步的。 */
    INSTANT_REFUND(CapabilityGroup.REFUND, "即时退款"),
    REFUND_QUERY(CapabilityGroup.REFUND, "退款查询"),

    /** 撤销/冲正：把当日已支付交易作废。国内特色，与 VOID 语义不同。 */
    REVERSE(CapabilityGroup.REFUND, "交易撤销"),

    // ---------- 订单管理 ----------
    ORDER_QUERY(CapabilityGroup.ORDER_OPS, "订单查询"),
    ORDER_CLOSE(CapabilityGroup.ORDER_OPS, "关闭订单"),
    ORDER_CANCEL(CapabilityGroup.ORDER_OPS, "取消订单"),

    // ---------- 异步通知 ----------
    ASYNC_NOTIFY(CapabilityGroup.NOTIFY, "异步通知"),

    /** 通知会重复投递，消费端必须幂等。几乎所有通道都是「至少一次」。 */
    NOTIFY_RETRY(CapabilityGroup.NOTIFY, "通知重试"),

    /** 通知可能乱序：先收到成功、后收到失败，或新旧通知交错。需用状态机+时间戳守卫。 */
    NOTIFY_OUT_OF_ORDER(CapabilityGroup.NOTIFY, "通知乱序"),

    // ---------- 币种 ----------
    MULTI_CURRENCY(CapabilityGroup.CURRENCY, "多币种"),

    /** 展示币种与结算币种分离：用户看到美元，商户结算到港币账户。 */
    PRESENTMENT_CURRENCY(CapabilityGroup.CURRENCY, "展示币种"),

    // ---------- 争议处理 ----------
    DISPUTE(CapabilityGroup.DISPUTE, "争议"),
    CHARGEBACK_REPRESENTMENT(CapabilityGroup.DISPUTE, "拒付申诉"),

    // ---------- 增值能力 ----------
    ESCROW(CapabilityGroup.VALUE_ADDED, "担保交易"),
    SETTLEMENT_SPLIT(CapabilityGroup.VALUE_ADDED, "分账"),
    RECURRING(CapabilityGroup.VALUE_ADDED, "订阅代扣"),
    ;

    private final CapabilityGroup group;
    private final String displayName;

    Capability(CapabilityGroup group, String displayName) {
        this.group = group;
        this.displayName = displayName;
    }

    public CapabilityGroup group() {
        return group;
    }

    public String displayName() {
        return displayName;
    }
}
