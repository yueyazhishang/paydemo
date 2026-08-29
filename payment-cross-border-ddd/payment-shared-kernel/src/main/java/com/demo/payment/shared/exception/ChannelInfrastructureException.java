package com.demo.payment.shared.exception;

/**
 * 通道基础设施异常 —— 唯一允许触发重试/切通道的异常类型。
 *
 * <p><b>为什么必须和 PaymentException 分开？</b>
 * 如果把"网络超时"和"余额不足"都抛成同一种异常，上层就无法区分
 * "该重试"和"该告诉用户换张卡"。结果是：要么对业务失败疯狂重试（浪费资源、
 * 可能被通道限流），要么对网络抖动直接失败（白白损失成功率）。
 *
 * <p>支付系统的通道成功率每提升 0.1% 都是真金白银，这个区分直接值钱。
 */
public class ChannelInfrastructureException extends PaymentException {

    /** 是否值得重试。证书错误、参数错误这类不重试；超时、限流、5xx 可重试 */
    private final boolean retryable;

    public ChannelInfrastructureException(String message, boolean retryable) {
        super("CHANNEL_INFRA_ERROR", message);
        this.retryable = retryable;
    }

    public ChannelInfrastructureException(String message, boolean retryable, Throwable cause) {
        super("CHANNEL_INFRA_ERROR", message, cause);
        this.retryable = retryable;
    }

    public boolean isRetryable() { return retryable; }
}
