package com.zx.payment.acquisition.domain.model;

/** 单次支付尝试的状态。生命周期被 Payment 聚合根统一管理。 */
public enum AttemptStatus {

    /** 已创建，尚未拿到通道单号。 */
    INITIATED,
    /** 已向通道下单，等待支付结果。 */
    PAYING,
    /** 本次尝试收款成功（可能是部分金额）。 */
    SUCCEEDED,
    /** 本次尝试失败（通道拒付、用户取消、超时）。 */
    FAILED,
    /** 因支付单关闭而作废。 */
    ABANDONED;

    public boolean isActive() {
        return this == INITIATED || this == PAYING;
    }

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == ABANDONED;
    }
}
