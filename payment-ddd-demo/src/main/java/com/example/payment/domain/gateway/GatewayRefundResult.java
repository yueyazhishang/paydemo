package com.example.payment.domain.gateway;

import lombok.Builder;
import lombok.Getter;

/**
 * 统一退款结果。
 * 注意渠道差异：微信退款为异步回调确认，支付宝/Stripe/PayPal 为同步返回——
 * 统一语义为 SUCCESS（终态成功）或 ACCEPTED（已受理，等待回调确认）。
 */
@Getter
@Builder
public class GatewayRefundResult {

    public enum RefundResultStatus { SUCCESS, ACCEPTED, FAILED }

    private final RefundResultStatus status;

    private final String channelRefundNo;

    private final String errorMessage;

    public static GatewayRefundResult success(String channelRefundNo) {
        return GatewayRefundResult.builder()
                .status(RefundResultStatus.SUCCESS).channelRefundNo(channelRefundNo).build();
    }

    public static GatewayRefundResult accepted(String channelRefundNo) {
        return GatewayRefundResult.builder()
                .status(RefundResultStatus.ACCEPTED).channelRefundNo(channelRefundNo).build();
    }

    public static GatewayRefundResult fail(String errorMessage) {
        return GatewayRefundResult.builder().status(RefundResultStatus.FAILED).errorMessage(errorMessage).build();
    }
}
