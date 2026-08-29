package com.example.payment.domain.payment.model;

/**
 * 退款单状态机：INIT → SUBMITTED → SUCCESS / FAILED。
 */
public enum RefundStatus {

    /** 已创建，未提交渠道 */
    INIT,
    /** 已提交渠道，等待结果（异步退款渠道等待回调） */
    SUBMITTED,
    /** 退款成功（终态） */
    SUCCESS,
    /** 退款失败（终态） */
    FAILED;

    public boolean isFinal() {
        return this == SUCCESS || this == FAILED;
    }
}
