package com.payment.application.payment.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 退款请求/响应DTO
 */
@Data
public class RefundRequest {
    
    /**
     * 退款单号(响应时使用)
     */
    private String refundId;
    
    /**
     * 支付订单ID
     */
    private String paymentId;
    
    /**
     * 退款金额
     */
    private BigDecimal refundAmount;
    
    /**
     * 退款原因
     */
    private String reason;
    
    /**
     * 回调URL
     */
    private String notifyUrl;
    
    /**
     * 状态(响应时使用)
     */
    private String status;
    
    /**
     * 状态描述(响应时使用)
     */
    private String statusDesc;
    
    /**
     * 错误信息(响应时使用)
     */
    private String errorMessage;
}
