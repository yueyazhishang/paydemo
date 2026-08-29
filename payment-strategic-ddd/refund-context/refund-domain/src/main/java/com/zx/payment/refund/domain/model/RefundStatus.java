package com.zx.payment.refund.domain.model;

/** 退款单状态。 */
public enum RefundStatus {

    /** 已受理，等待通道处理。 */
    PROCESSING,
    /** 退款成功（资金已退回）。 */
    SUCCEEDED,
    /** 退款失败。 */
    FAILED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED;
    }
}
