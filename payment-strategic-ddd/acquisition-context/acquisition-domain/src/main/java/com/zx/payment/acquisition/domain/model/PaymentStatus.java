package com.zx.payment.acquisition.domain.model;

/**
 * 支付单状态机（收单上下文核心不变量之一）。
 *
 *                    ┌──────────┐
 *                    │  CREATED │  已创建，尚未向任何通道下单
 *                    └────┬─────┘
 *                         │ 首次下单（选中通道）
 *                         ▼
 *          ┌─────────► ┌────────┐ ◄─────────┐
 *          │ 换通道重试  │ PAYING │  继续支付   │
 *          │           └───┬────┘           │
 *          │               │                │
 *     失败 │      ┌────────┼────────┐       │ 部分成功
 *          │      ▼        ▼        ▼       │
 *          │  ┌────────┐ ┌───────┐ ┌────────┴──┐
 *          └──┤ FAILED │ │SUCCESS│ │  PARTIAL  │ 部分支付，累计已收 < 应付
 *             └────────┘ └───────┘ └─────┬─────┘
 *                                        │ 补齐付清
 *                                        ▼
 *                                      SUCCESS
 *
 *             CREATED / PAYING / PARTIAL / FAILED ──关单──► CLOSED
 *
 * 相比 v1 补上的两个真实场景：
 *  1. PARTIAL（部分支付）：一张单分多次付清，累计已收金额独立跟踪。
 *     真实业务里确实存在（余额 + 银行卡组合支付、分次付款），v1 没有这个状态。
 *  2. FAILED 不再是死路：允许换通道重试，这是提升支付成功率的关键手段。
 *
 * 终态：SUCCESS（付清）、CLOSED（关闭）。FAILED 可重试，故不是终态。
 */
public enum PaymentStatus {

    CREATED,
    PAYING,
    PARTIAL,
    SUCCESS,
    FAILED,
    CLOSED;

    /** 是否终态（不可再迁移到任何其他状态）。 */
    public boolean isFinal() {
        return this == SUCCESS || this == CLOSED;
    }

    /** 是否处于"钱已收到"的状态——只有这两种才允许退款。 */
    public boolean hasReceivedFunds() {
        return this == SUCCESS || this == PARTIAL;
    }

    /** 是否还能继续发起支付尝试（未被关闭、未付清）。 */
    public boolean canStartAttempt() {
        return this == CREATED || this == PAYING || this == PARTIAL || this == FAILED;
    }
}
