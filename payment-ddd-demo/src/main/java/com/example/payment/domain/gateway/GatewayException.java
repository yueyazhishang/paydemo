package com.example.payment.domain.gateway;

/**
 * 网关统一异常（防腐层）：渠道 SDK/协议异常在此被翻译为统一领域异常，
 * 避免第三方异常类型渗透进领域层与应用层。
 */
public class GatewayException extends RuntimeException {

    public GatewayException(String message) {
        super(message);
    }

    public GatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}
