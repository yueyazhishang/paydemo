package com.example.payment.domain.payment.model;

/**
 * 支付单状态机：INIT → PAYING → SUCCESS / FAILED / CLOSED。
 * 终态不可逆，流转规则由聚合根守护（见 PaymentOrder 的 transition 校验）。
 */
public enum PaymentStatus {

    /** 已创建，未提交渠道 */
    INIT,
    /** 已提交渠道，等待用户支付 */
    PAYING,
    /** 支付成功（终态） */
    SUCCESS,
    /** 支付失败（终态） */
    FAILED,
    /** 已关单（终态） */
    CLOSED;

    public boolean isFinal() {
        return this == SUCCESS || this == FAILED || this == CLOSED;
    }
}
