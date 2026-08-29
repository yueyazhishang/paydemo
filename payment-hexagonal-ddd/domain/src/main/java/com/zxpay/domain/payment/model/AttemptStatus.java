package com.zxpay.domain.payment.model;

/**
 * 单次通道尝试的状态。
 *
 * <p>与 {@link PaymentStatus}（订单级）区分开：订单只有一个状态，
 * 但可能有多次尝试，每次尝试有自己的状态。
 *
 * <p>{@link #UNKNOWN} 是最需要单独建模的一个：
 * 请求发出去了，没拿到可信响应（超时、连接中断、响应体无法解析）。
 * 此时<b>通道侧很可能已经受理甚至扣款成功</b>。
 * 把 UNKNOWN 简单归并到 FAILED，是「用户已付款但订单显示失败」的直接原因。
 */
public enum AttemptStatus {

    /** 已创建，尚未下发。 */
    CREATED("已创建"),

    /** 已下发通道，等待结果。 */
    SUBMITTED("已提交"),

    /** 通道明确返回成功（已扣款或已授权）。 */
    SUCCEEDED("成功"),

    /** 通道明确返回失败。 */
    FAILED("失败"),

    /** 结果未知：请求已发出但响应不可信。必须查单后才能定论。 */
    UNKNOWN("结果未知"),

    /** 已被切换放弃：换到别的通道重试，本次尝试不再推进。 */
    SWITCHED_OUT("已切换放弃"),
    ;

    private final String displayName;

    AttemptStatus(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == SWITCHED_OUT;
    }

    /** 是否可以复用本次尝试重试（复用才能保住通道幂等键）。 */
    public boolean retryable() {
        return this == CREATED || this == SUBMITTED || this == UNKNOWN;
    }
}
