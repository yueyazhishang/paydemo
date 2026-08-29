package com.example.payment.application.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * 退款结果 DTO。
 */
@Getter
@Builder
public class RefundOrderDTO {

    private String refundId;
    private String paymentId;
    private Long refundAmountMinor;
    private String currency;
    private String status;
    private String channelRefundNo;
}
