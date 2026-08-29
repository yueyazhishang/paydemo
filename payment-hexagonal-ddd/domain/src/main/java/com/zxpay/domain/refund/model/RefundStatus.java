package com.zxpay.domain.refund.model;

/**
 * 退款单状态。
 *
 * <p>与 {@code PaymentStatus} 的关系：支付单上只有
 * {@code REFUNDING / PARTIAL_REFUNDED / REFUNDED} 三个粗粒度状态，
 * 细节全在退款单上。这样设计的好处是支付单保持轻量，
 * 而每一笔退款的完整生命周期（提交、处理中、成功、失败、失败原因）都可追溯。
 *
 * <p>{@link #PROCESSING} 是海外退款的典型状态：卡组织退款不是即时到账的，
 * 通道受理后可能要 5~10 个工作日才真正到用户账上。
 * 国内退款虽然也异步，但通常秒级到几分钟完成。
 * 这个差异会影响商户侧的展示文案与客服话术。
 */
public enum RefundStatus {

    /** 已创建，尚未提交通道。 */
    CREATED("已创建"),

    /** 已提交通道，等待通道受理。 */
    SUBMITTED("已提交"),

    /** 通道已受理，退款处理中（资金在途）。 */
    PROCESSING("退款中"),

    /** 退款成功。终态。 */
    SUCCEEDED("退款成功"),

    /** 退款失败。终态。可重新发起。 */
    FAILED("退款失败"),

    /** 已取消（商户在提交前撤回）。终态。 */
    CANCELLED("已取消"),
    ;

    private final String displayName;

    RefundStatus(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }

    /** 是否已进入通道处理流程（提交后不可再取消）。 */
    public boolean submitted() {
        return this == SUBMITTED || this == PROCESSING || this == SUCCEEDED;
    }
}
