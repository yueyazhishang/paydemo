package com.zxpay.interfaces.rest;

import com.zxpay.sharedkernel.exception.ConcurrencyConflictException;
import com.zxpay.sharedkernel.exception.DomainException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

/**
 * 全局异常处理：把领域异常翻译成机器可读的错误码。
 *
 * <p>关键区分：<b>领域异常 vs 系统异常</b>。
 *
 * <ul>
 *   <li><b>领域异常</b>（{@link DomainException}）：请求本身不合法。
 *       返回 4xx + 明确错误码，商户据此决定是否重试。
 *       例如 {@code REFUND_NOT_ELIGIBLE} 表示这笔退款做不了，
 *       重试一万次也没用。</li>
 *   <li><b>并发冲突</b>（{@link ConcurrencyConflictException}）：
 *       乐观锁冲突，本质是「并发下请重试」，返回 409。
 *       客户端应当重新查询最新状态再决定，而不是盲目重试。</li>
 *   <li><b>系统异常</b>：我们的 bug 或依赖故障，返回 5xx 并告警。
 *       绝不把内部堆栈抛给商户——既不安全也不专业。</li>
 * </ul>
 *
 * <p>错误码必须稳定且可枚举，商户会按它写分支逻辑。
 * 改动错误码等于破坏对外契约，需要走版本流程。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ConcurrencyConflictException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(ConcurrencyConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body(e.code(), e.getMessage()));
    }

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<Map<String, Object>> handleDomain(DomainException e) {
        // 领域异常是预期内的业务拒绝，记 INFO 即可，不该触发告警
        log.info("domain exception: {} - {}", e.code(), e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body(e.code(), e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(body("INVALID_ARGUMENT", e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception e) {
        // 系统异常必须记完整堆栈，这是排查线上问题的唯一线索
        log.error("unexpected error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body("INTERNAL_ERROR", "服务暂时不可用，请稍后重试"));
    }

    private Map<String, Object> body(String code, String message) {
        return Map.of(
                "code", code,
                "message", message == null ? "" : message,
                "timestamp", Instant.now().toString());
    }
}
