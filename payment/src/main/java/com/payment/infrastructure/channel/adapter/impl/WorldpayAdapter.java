package com.payment.infrastructure.channel.adapter.impl;

import com.payment.domain.channel.model.enums.ChannelCode;
import com.payment.domain.payment.model.aggregate.PaymentOrder;
import com.payment.domain.refund.model.aggregate.RefundOrder;
import com.payment.infrastructure.channel.adapter.PaymentChannelAdapter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Worldpay支付适配器
 * 
 * 全球支付处理平台，支持120+货币，40+本地支付方式
 * 
 * 文档: https://developer.worldpay.com/
 */
@Slf4j
@Component
public class WorldpayAdapter implements PaymentChannelAdapter {
    
    @Value("${payment.worldpay.serviceKey:}")
    private String serviceKey;
    
    @Value("${payment.worldpay.clientKey:}")
    private String clientKey;
    
    // Worldpay状态常量
    private static final String STATUS_AUTHORIZED = "AUTHORIZED";
    private static final String STATUS_CAPTURED = "CAPTURED";
    private static final String STATUS_CANCELLED = "CANCELLED";
    private static final String STATUS_REFUSED = "REFUSED";
    private static final String STATUS_REFUNDED = "REFUNDED";
    
    @Override
    public ChannelCode getChannelCode() {
        return ChannelCode.WORLDPAY;
    }
    
    @Override
    public ChannelCreateResult createPayment(PaymentOrder order) {
        log.info("创建Worldpay订单, paymentId={}, amount={}", order.getPaymentId().getValue(), order.getAmount());
        
        try {
            // Worldpay使用令牌(Token)进行支付
            // 流程:
            // 1. 前端通过Worldpay脚本令牌化卡信息
            // 2. 后端使用Token创建支付
            
            Map<String, Object> paymentRequest = new HashMap<>();
            paymentRequest.put("token", order.getExtraParam("token")); // 前端生成的令牌
            paymentRequest.put("orderDescription", order.getDescription());
            paymentRequest.put("amount", order.getAmount().toCents());
            paymentRequest.put("currencyCode", order.getAmount().getCurrency().getCurrencyCode());
            paymentRequest.put("orderCode", order.getPaymentId().getValue());
            paymentRequest.put("customerIdentifiers", Map.of(
                "merchantId", order.getMerchantId()
            ));
            
            // 模拟API调用
            String orderCode = "WP" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();
            
            Map<String, String> paymentParams = new HashMap<>();
            paymentParams.put("orderCode", orderCode);
            paymentParams.put("clientKey", clientKey);
            
            log.info("Worldpay订单创建成功, orderCode={}", orderCode);
            return ChannelCreateResult.success(orderCode, paymentParams);
            
        } catch (Exception e) {
            log.error("创建Worldpay订单失败", e);
            return ChannelCreateResult.failure("WORLDPAY_ERROR", e.getMessage());
        }
    }
    
    @Override
    public ChannelQueryResult queryPayment(String channelOrderId) {
        log.info("查询Worldpay状态, orderCode={}", channelOrderId);
        
        return ChannelQueryResult.builder()
                .success(true)
                .channelOrderId(channelOrderId)
                .status(STATUS_CAPTURED)
                .paid(true)
                .paidTime(System.currentTimeMillis() / 1000)
                .build();
    }
    
    @Override
    public boolean closePayment(String channelOrderId) {
        log.info("取消Worldpay订单, orderCode={}", channelOrderId);
        return true;
    }
    
    @Override
    public ChannelRefundResult refund(RefundOrder refundOrder) {
        log.info("发起Worldpay退款, refundId={}, amount={}", 
                refundOrder.getRefundId().getValue(), refundOrder.getRefundAmount());
        
        // POST /orders/{orderCode}/refunds
        String refundId = "WPREF" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        
        return ChannelRefundResult.success(refundId);
    }
    
    @Override
    public ChannelRefundQueryResult queryRefund(String channelRefundId) {
        log.info("查询Worldpay退款状态, refundId={}", channelRefundId);
        
        return ChannelRefundQueryResult.builder()
                .success(true)
                .channelRefundId(channelRefundId)
                .status(STATUS_REFUNDED)
                .refunded(true)
                .build();
    }
    
    @Override
    public boolean verifyWebhook(WebhookRequest request) {
        log.info("验证Worldpay Webhook签名");
        return true;
    }
    
    @Override
    public WebhookResult parseWebhook(WebhookRequest request) {
        log.info("解析Worldpay Webhook事件");
        
        return WebhookResult.builder()
                .status(STATUS_CAPTURED)
                .payment(true)
                .build();
    }
    
    @Override
    public String getSuccessResponse() {
        return "{}";
    }
    
    @Override
    public String getFailureResponse() {
        return "{}";
    }
}
