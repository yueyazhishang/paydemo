package com.payment.application.payment.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 创建支付响应DTO
 */
@Data
@Builder
public class CreatePaymentResponse {
    
    /**
     * 支付订单ID
     */
    private String paymentId;
    
    /**
     * 商户订单号
     */
    private String orderId;
    
    /**
     * 支付金额
     */
    private String amount;
    
    /**
     * 货币
     */
    private String currency;
    
    /**
     * 支付状态
     */
    private String status;
    
    /**
     * 支付状态描述
     */
    private String statusDesc;
    
    /**
     * 通道编码
     */
    private String channelCode;
    
    /**
     * 通道名称
     */
    private String channelName;
    
    /**
     * 支付参数(用于前端发起支付)
     * 根据不同的通道返回不同的参数格式
     */
    private Map<String, String> paymentParams;
    
    /**
     * 过期时间
     */
    private LocalDateTime expireTime;
    
    /**
     * 创建时间
     */
    private LocalDateTime createdAt;
}
