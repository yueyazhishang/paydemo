package com.example.payment.domain.gateway;

import com.example.payment.domain.shared.Money;
import lombok.Builder;
import lombok.Getter;

/**
 * 统一退款请求。
 */
@Getter
@Builder
public class GatewayRefundRequest {

    /** 我方退款单号（渠道 out_refund_no / refund id 语义映射源） */
    private final String refundId;

    private final String paymentId;

    /** 渠道支付流水号 */
    private final String channelTradeNo;

    private final Money refundAmount;

    /** 退款原因 */
    private final String reason;
}
