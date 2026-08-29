package com.demo.payment.domain.acquiring.statemachine;

/**
 * 支付单状态。
 *
 * <h3>为什么状态划分成这样</h3>
 * <p>核心矛盾是：<b>国内通道是一段式，海外卡组织是两段式</b>。
 * 若按国内模型设计，接入 Stripe 后就没法表达"已授权未请款"；
 * 若按两段式统一设计，国内通道的 AUTHORIZED 就成了永远跳过的空转态。
 *
 * <p><b>本设计的取舍</b>：状态全集保留两段式（AUTHORIZED / CAPTURING），
 * 但由 {@link ChannelCapability#authCaptureSeparated()} 决定一段式通道
 * 是否跳过这两个态。牺牲一点"统一性"，换取对两类通道的<b>准确表达</b>——
 * 这是值得的，因为错误的统一会导致无法支持预授权业务。
 */
public enum PaymentStatus {

    /** 已创建：支付单已落库，尚未向通道发起请求 */
    CREATED,

    /** 支付中：已拿到通道支付凭证（如微信 prepay_id），等待用户付款 */
    PAYING,

    /** 已授权：两段式通道已冻结买家额度，等待商户请款（酒店/租车预授权） */
    AUTHORIZED,

    /** 请款中：已发起 capture，等待通道确认 */
    CAPTURING,

    /** 已支付：资金已到账。<b>非终态</b>，可继续退款 */
    PAID,

    /** 部分退款：已退部分金额，仍可继续退 */
    PARTIALLY_REFUNDED,

    /** 全额退款：终态 */
    REFUNDED,

    /** 已关闭：订单超时/用户取消/主动关单。终态 */
    CLOSED,

    /** 支付失败：通道明确返回失败。终态 */
    FAILED,
    ;

    /**
     * 是否为终态。
     *
     * <p><b>这是整个支付系统最重要的一行判断。</b>
     * 异步回调存在乱序可能：先收到"支付成功"，后收到"支付失败"（通道补偿通知、
     * 网络重投、MQ 重放都可能造成）。若没有终态守卫，后到的失败通知会把已成功的订单
     * 改成失败 —— 用户付了钱，订单却是失败，这是<b>直接资损</b>。
     *
     * <p>终态一律拒绝任何状态变更，只记录异常日志并告警。
     */
    public boolean isTerminal() {
        return this == REFUNDED || this == CLOSED || this == FAILED;
    }

    /** 是否已经收到钱（用于判断是否可退款、可结算） */
    public boolean isPaid() {
        return this == PAID || this == PARTIALLY_REFUNDED || this == AUTHORIZED || this == CAPTURING;
    }

    /** 是否处于处理中，需要查证补偿 */
    public boolean isProcessing() {
        return this == CREATED || this == PAYING || this == AUTHORIZED || this == CAPTURING;
    }
}
