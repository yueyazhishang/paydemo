package com.demo.payment.application.command;

import com.demo.payment.domain.acquiring.model.aggregate.PaymentOrder;

import java.util.Map;

/**
 * 支付受理结果（返回给接入层）。
 */
public record PayResult(
        String paymentOrderId,
        String merchantOrderNo,
        String status,
        String amount,
        /** 支付凭证，透传给前端用于拉起支付 */
        Map<String, String> credential,
        String message
) {
    public static PayResult of(PaymentOrder order, String message) {
        var attempt = order.currentAttempt();
        Map<String, String> cred = attempt != null
                ? Map.of("outTradeNo", attempt.outTradeNo().value(),
                         "channel", attempt.channelCode().code())
                : Map.of();
        return new PayResult(order.id().value(), order.merchantOrderNo(),
                order.status().name(), order.amount().toString(), cred, message);
    }

    public static PayResult parse(String snapshot) {
        // 简化实现：真实场景用 JSON 序列化
        return new PayResult(snapshot, "", "", "", Map.of(), "FROM_IDEMPOTENCY_CACHE");
    }
}
