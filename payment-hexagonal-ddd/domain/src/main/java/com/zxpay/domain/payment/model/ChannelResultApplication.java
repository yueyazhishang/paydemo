package com.zxpay.domain.payment.model;

/**
 * 通道结果应用到支付单后的处理结果。
 *
 * <p>为什么不用「抛异常」表达失败？
 *
 * <p>因为通道回调这个场景下，<b>抛异常是最糟的选择</b>：
 * 我们的回调接口返回 5xx，通道会按重试策略反复推送，
 * 于是同一笔问题被放大 15 次，告警淹没一切，而问题本身一点没解决。
 *
 * <p>正确做法是：<b>先收下、再分类</b>。回调接口一律快速返回成功，
 * 把「处理不了」的情况沉淀成状态，交给补偿任务或人工处理。
 *
 * <p>{@link #TERMINAL_CONFLICT_PAID_AFTER_CLOSE} 是其中最关键的一类：
 * 订单已关闭（超时/商户取消），但通道通知说用户付款成功了。
 * 这意味着<b>钱已经进了我们的账，订单却是关闭状态</b>——
 * 既不会发货也不会退款，钱凭空消失在账务里。
 * 这种情况必须触发自动原路退款，而不是记录一条异常就算完。
 */
public enum ChannelResultApplication {

    /** 状态已正常推进。 */
    APPLIED("已应用"),

    /** 重复通知，状态未变化（幂等命中）。 */
    IGNORED_DUPLICATE("重复通知已忽略"),

    /** 订单处于终态，本次结果不影响状态。 */
    IGNORED_TERMINAL("终态已忽略"),

    /**
     * 终态冲突：订单已关闭但通道侧支付成功。
     * <b>必须触发自动原路退款或人工介入，否则形成资金黑洞。</b>
     */
    TERMINAL_CONFLICT_PAID_AFTER_CLOSE("终态冲突：关闭后收到支付成功"),

    /** 金额不符：通道实付金额与订单金额不一致，需人工核对。 */
    AMOUNT_MISMATCH("金额不符"),

    /** 结果未知，已保留现场，需主动查单确认。 */
    UNKNOWN_NEEDS_QUERY("结果未知待查单"),
    ;

    private final String displayName;

    ChannelResultApplication(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /** 是否需要后续人工或自动补偿介入。 */
    public boolean requiresFollowUp() {
        return this == TERMINAL_CONFLICT_PAID_AFTER_CLOSE
                || this == AMOUNT_MISMATCH
                || this == UNKNOWN_NEEDS_QUERY;
    }
}
