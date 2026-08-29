package com.example.payment.domain.reconciliation.model;

/**
 * 对账差异类型：
 * LOCAL_MORE —— 我方成功、渠道账单缺失（少收款风险，重点告警）
 * CHANNEL_MORE —— 渠道成功、我方未终态（典型掉单，应以渠道为准修复状态）
 * AMOUNT_MISMATCH —— 双方都成功但金额不一致（串单/篡改风险）
 */
public enum DiffType {

    LOCAL_MORE,
    CHANNEL_MORE,
    AMOUNT_MISMATCH
}
