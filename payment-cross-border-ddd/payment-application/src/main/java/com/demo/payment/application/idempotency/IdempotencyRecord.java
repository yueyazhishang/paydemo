package com.demo.payment.application.idempotency;

import java.time.Instant;

/**
 * 幂等记录。
 *
 * <p><b>为什么需要 {@code requestFingerprint}？</b>
 * 只记录幂等键是不够的。同一个幂等键，若携带不同的业务参数
 * （比如金额从 100 变成 200），说明客户端有 bug。
 * 此时必须<b>拒绝并报 409</b>，而不是静默返回第一次的结果 ——
 * 否则用户以为付了 200，实际只扣了 100，这是资损。
 */
public record IdempotencyRecord(
        String idempotencyKey,
        String requestFingerprint,
        IdempotencyStatus status,
        String resultSnapshot,
        Instant createdAt,
        Instant expireAt
) {

    public enum IdempotencyStatus {
        /** 处理中：请求已受理，尚未完成 */
        PROCESSING,
        /** 已完成：可安全返回快照结果 */
        COMPLETED,
        /** 失败：可安全重试（仅指业务失败，且失败本身是幂等的） */
        FAILED
    }

    public boolean isExpired(Instant now) {
        return expireAt != null && now.isAfter(expireAt);
    }

    public boolean matches(String fingerprint) {
        return requestFingerprint == null || requestFingerprint.equals(fingerprint);
    }
}
