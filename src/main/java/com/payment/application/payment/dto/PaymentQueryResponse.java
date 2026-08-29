package com.payment.application.payment.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 支付查询响应DTO
 */
@Data
@Builder
public class PaymentQueryResponse {
    
    private String paymentId;
    private String orderId;
    private String merchantId;
    private String amount;
    private String currency;
    private String status;
    private String statusDesc;
    private String channelCode;
    private String channelName;
    private String channelOrderId;
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime successAt;
}
