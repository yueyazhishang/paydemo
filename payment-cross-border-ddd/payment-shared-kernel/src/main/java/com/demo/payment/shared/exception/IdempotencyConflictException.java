package com.demo.payment.shared.exception;

/**
 * 幂等冲突：同一个幂等键，携带了不同的业务参数。
 *
 * <p>这是<b>客户端 bug 的信号</b>，必须暴露而不是容错。
 * 典型场景：前端重试时把金额从 100 改成了 200 却复用了同一个幂等键。
 * 若系统"善意地"按新参数处理，就会造成用户预期与实际扣款不一致。
 */
public class IdempotencyConflictException extends PaymentException {

    private final String idempotencyKey;

    public IdempotencyConflictException(String idempotencyKey, String message) {
        super("IDEMPOTENCY_CONFLICT", message);
        this.idempotencyKey = idempotencyKey;
    }

    public String idempotencyKey() { return idempotencyKey; }
}
