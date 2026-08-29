package com.example.payment.interfaces.rest;

import com.example.payment.shared.ApiResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理：把领域/应用层异常翻译为稳定的外部错误契约。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务规则异常（状态机拒绝、可退金额不足、参数校验等） */
    @ExceptionHandler({IllegalStateException.class, IllegalArgumentException.class})
    public ResponseEntity<ApiResult<Void>> handleBusinessException(Exception e) {
        log.warn("业务异常: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResult.fail("BUSINESS_ERROR", e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResult<Void>> handleUnexpected(Exception e) {
        log.error("系统异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResult.fail("SYSTEM_ERROR", "系统繁忙，请稍后重试"));
    }
}
