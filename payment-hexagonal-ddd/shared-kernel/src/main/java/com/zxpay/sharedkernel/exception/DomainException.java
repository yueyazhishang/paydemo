package com.zxpay.sharedkernel.exception;

/**
 * 领域异常：业务规则被违反时抛出。
 *
 * <p>与「系统异常」严格区分。领域异常意味着<b>请求本身不合法</b>（余额不足、状态不允许、
 * 金额超限），调用方拿到后应当转换成明确的业务错误码返回给商户，而不是触发重试或告警。
 *
 * <p>{@code code} 用大写下划线风格，如 {@code PAYMENT_STATUS_INVALID}，可直接映射对外错误码。
 */
public class DomainException extends RuntimeException {

    private final String code;

    public DomainException(String code, String message) {
        super(message);
        this.code = code;
    }

    public DomainException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
