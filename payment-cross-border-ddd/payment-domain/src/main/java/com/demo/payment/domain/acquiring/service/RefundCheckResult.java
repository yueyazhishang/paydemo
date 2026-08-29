package com.demo.payment.domain.acquiring.service;

/**
 * 退款校验结果。用结果对象而非抛异常，便于批量校验场景收集所有问题。
 */
public record RefundCheckResult(boolean allowed, String rejectReason) {

    private static final RefundCheckResult OK = new RefundCheckResult(true, null);

    public static RefundCheckResult ok() { return OK; }
    public static RefundCheckResult reject(String reason) { return new RefundCheckResult(false, reason); }
}
