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
 * Stripe支付适配器
 * 
 * 支持信用卡、借记卡、Apple Pay、Google Pay、Alipay、WeChat Pay等
 * 
 * 参考文档: https://stripe.com/docs/api
 */
@Slf4j
@Component
public class StripeAdapter implements PaymentChannelAdapter {
    
    @Value("${payment.stripe.apiKey:}")
    private String apiKey;
    
    @Value("${payment.stripe.webhookSecret:}")
    private String webhookSecret;
    
    @Value("${payment.stripe.apiBase:https://api.stripe.com}")
    private String apiBase;
    
    // Stripe状态常量
    private static final String STATUS_REQUIRES_PAYMENT_METHOD = "requires_payment_method";
    private static final String STATUS_REQUIRES_CONFIRMATION = "requires_confirmation";
    private static final String STATUS_REQUIRES_ACTION = "requires_action";
    private static final String STATUS_PROCESSING = "processing";
    private static final String STATUS_SUCCEEDED = "succeeded";
    private static final String STATUS_CANCELED = "canceled";
    
    @Override
    public ChannelCode getChannelCode() {
        return ChannelCode.STRIPE;
    }
    
    @Override
    public ChannelCreateResult createPayment(PaymentOrder order) {
        log.info("创建Stripe订单, paymentId={}, amount={}", order.getPaymentId().getValue(), order.getAmount());
        
        try {
            // Stripe使用PaymentIntent作为支付单元
            // 1. 创建PaymentIntent
            Map<String, Object> paymentIntentParams = new HashMap<>();
            paymentIntentParams.put("amount", order.getAmount().toCents());
            paymentIntentParams.put("currency", order.getAmount().getCurrency().getCurrencyCode().toLowerCase());
            paymentIntentParams.put("description", order.getDescription());
            paymentIntentParams.put("metadata", Map.of(
                "paymentId", order.getPaymentId().getValue(),
                "merchantOrderId", order.getMerchantOrderId().getValue()
            ));
            
            // 自动确认，自动捕获
            paymentIntentParams.put("confirm", true);
            paymentIntentParams.put("automatic_payment_methods", Map.of("enabled", true));
            
            // 模拟API调用
            String paymentIntentId = "pi_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
            String clientSecret = paymentIntentId + "_secret_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
            
            Map<String, String> paymentParams = new HashMap<>();
            paymentParams.put("clientSecret", clientSecret);
            paymentParams.put("publishableApiKey", apiKey.substring(0, 8) + "...");
            
            // 根据支付方式返回不同参数
            String paymentMethod = order.getExtraParam("paymentMethod");
            if ("alipay".equals(paymentMethod)) {
                paymentParams.put("paymentMethod", "alipay");
            } else if ("wechat_pay".equals(paymentMethod)) {
                paymentParams.put("paymentMethod", "wechat_pay");
            }
            
            log.info("Stripe订单创建成功, paymentIntentId={}", paymentIntentId);
            return ChannelCreateResult.success(paymentIntentId, paymentParams);
            
        } catch (Exception e) {
            log.error("创建Stripe订单失败", e);
            return ChannelCreateResult.failure("STRIPE_ERROR", e.getMessage());
        }
    }
    
    @Override
    public ChannelQueryResult queryPayment(String channelOrderId) {
        log.info("查询Stripe状态, paymentIntentId={}", channelOrderId);
        
        // 调用Stripe API查询PaymentIntent
        // GET /v1/payment_intents/{id}
        
        return ChannelQueryResult.builder()
                .success(true)
                .channelOrderId(channelOrderId)
                .status(STATUS_SUCCEEDED)
                .paid(true)
                .paidTime(System.currentTimeMillis() / 1000)
                .build();
    }
    
    @Override
    public boolean closePayment(String channelOrderId) {
        log.info("取消Stripe订单, paymentIntentId={}", channelOrderId);
        // 调用Stripe取消PaymentIntent API
        // POST /v1/payment_intents/{id}/cancel
        return true;
    }
    
    @Override
    public ChannelRefundResult refund(RefundOrder refundOrder) {
        log.info("发起Stripe退款, refundId={}, amount={}", 
                refundOrder.getRefundId().getValue(), refundOrder.getRefundAmount());
        
        // Stripe通过PaymentIntent创建Refund
        // POST /v1/refunds
        
        String refundId = "re_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        
        return ChannelRefundResult.success(refundId);
    }
    
    @Override
    public ChannelRefundQueryResult queryRefund(String channelRefundId) {
        log.info("查询Stripe退款状态, refundId={}", channelRefundId);
        
        // GET /v1/refunds/{id}
        
        return ChannelRefundQueryResult.builder()
                .success(true)
                .channelRefundId(channelRefundId)
                .status("succeeded")
                .refunded(true)
                .build();
    }
    
    @Override
    public boolean verifyWebhook(WebhookRequest request) {
        // Stripe使用webhook签名验证
        // 1. 从header获取Stripe-Signature
        // 2. 使用webhookSecret计算HMAC-SHA256
        // 3. 比较签名
        log.info("验证Stripe Webhook签名");
        
        String signature = request.getSignature();
        // 实际验证逻辑...
        return true;
    }
    
    @Override
    public WebhookResult parseWebhook(WebhookRequest request) {
        // 解析Stripe Webhook事件
        // Stripe事件类型: payment_intent.succeeded, charge.refunded等
        log.info("解析Stripe Webhook事件");
        
        return WebhookResult.builder()
                .status("succeeded")
                .payment(true)
                .build();
    }
    
    @Override
    public String getSuccessResponse() {
        // Stripe不需要返回，使用HTTP状态码
        return "";
    }
    
    @Override
    public String getFailureResponse() {
        return "";
    }
    
    /**
     * 根据金额确定Stripe支持的支付方式
     */
    private Map<String, Object> getPaymentMethodTypes(PaymentOrder order) {
        String currency = order.getAmount().getCurrency().getCurrencyCode();
        
        Map<String, Object> automaticMethods = new HashMap<>();
        automaticMethods.put("enabled", true);
        
        return automaticMethods;
    }
}
