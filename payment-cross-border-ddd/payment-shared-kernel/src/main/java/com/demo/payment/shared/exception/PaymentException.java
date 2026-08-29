package com.demo.payment.shared.exception;

/**
 * 支付业务异常基类。
 *
 * <p>区分三类异常是支付系统错误处理的关键：
 * <ul>
 *   <li>{@code PaymentException}：业务规则拒绝（余额不足、超过限额、订单已关闭）。
 *       <b>不需要重试</b>，直接返回用户。</li>
 *   <li>{@code ChannelInfrastructureException}：基础设施故障（网络超时、证书缺失）。
 *       <b>需要重试或切通道</b>。</li>
 *   <li>{@code IdempotencyConflictException}：幂等键冲突（同 key 不同参数）。
 *       <b>必须返回 409</b>，绝不能当作新请求处理。</li>
 * </ul>
 */
public class PaymentException extends RuntimeException {

    private final String code;

    public PaymentException(String code, String message) {
        super(message);
        this.code = code;
    }

    public PaymentException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() { return code; }
}
