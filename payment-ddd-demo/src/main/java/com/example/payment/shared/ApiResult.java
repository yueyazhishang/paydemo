package com.example.payment.shared;

import lombok.Getter;

/**
 * 统一 REST 响应包装。
 */
@Getter
public class ApiResult<T> {

    private final boolean success;
    private final String code;
    private final String message;
    private final T data;

    private ApiResult(boolean success, String code, String message, T data) {
        this.success = success;
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> ApiResult<T> ok(T data) {
        return new ApiResult<>(true, "SUCCESS", "ok", data);
    }

    public static <T> ApiResult<T> fail(String code, String message) {
        return new ApiResult<>(false, code, message, null);
    }
}
