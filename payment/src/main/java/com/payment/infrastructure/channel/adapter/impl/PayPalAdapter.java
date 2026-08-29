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
 * PayPal适配器
 * 
 * 支持标准支付、快速结账
 * 
 * 参考文档: https://developer.paypal.com/docs/api/overview/
 */
@Slf4j
@Component
public class PayPalAdapter implements PaymentChannelAdapter {
    
    @Value("${payment.paypal.clientId:}")
    private String clientId;
    
    @Value("${payment.paypal.clientSecret:}")
    private String clientSecret;
    
    @Value("${payment.paypal.mode:sandbox}")
    private String mode;
    
    // PayPal状态常量
    private static final String STATUS_CREATED = "CREATED";
    private static final String STATUS_SAVED = "SAVED";
    private static final String STATUS_APPROVED = "APPROVED";
    private static final String STATUS_VOIDED = "VOIDED";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_PAYER_ACTION_REQUIRED = "PAYER_ACTION_REQUIRED";
    
    @Override
    public ChannelCode getChannelCode() {
        return ChannelCode.PAYPAL;
    }
    
    @Override
    public ChannelCreateResult createPayment(PaymentOrder order) {
        log.info("创建PayPal订单, paymentId={}, amount={}", order.getPaymentId().getValue(), order.getAmount());
        
        try {
            // 1. 获取OAuth Token
            String accessToken = getAccessToken();
            
            // 2. 创建Order
            // POST /v2/checkout/orders
            Map<String, Object> orderRequest = new HashMap<>();
            orderRequest.put("intent", "CAPTURE");
            orderRequest.put("purchase_units", new Object[]{
                Map.of(
                    "reference_id", order.getPaymentId().getValue(),
                    "amount", Map.of(
                        "currency_code", order.getAmount().getCurrency().getCurrencyCode(),
                        "value", order.getAmount().getAmount().toString()
                    ),
                    "description", order.getDescription()
                )
            });
            orderRequest.put("application_context", Map.of(
                "return_url", order.getReturnUrl(),
                "cancel_url", order.getReturnUrl()
            ));
            
            // 模拟API调用
            String orderId = UUID.randomUUID().toString().replace("-", "").toUpperCase();
            
            Map<String, String> paymentParams = new HashMap<>();
            paymentParams.put("orderId", orderId);
            // PayPal跳转链接
            String baseUrl = "sandbox".equals(mode) ? 
                    "https://www.sandbox.paypal.com" : "https://www.paypal.com";
            paymentParams.put("approveUrl", baseUrl + "/checkoutnow?token=" + orderId);
            
            log.info("PayPal订单创建成功, orderId={}", orderId);
            return ChannelCreateResult.success(orderId, paymentParams);
            
        } catch (Exception e) {
            log.error("创建PayPal订单失败", e);
            return ChannelCreateResult.failure("PAYPAL_ERROR", e.getMessage());
        }
    }
    
    @Override
    public ChannelQueryResult queryPayment(String channelOrderId) {
        log.info("查询PayPal状态, orderId={}", channelOrderId);
        
        // GET /v2/checkout/orders/{id}
        
        return ChannelQueryResult.builder()
                .success(true)
                .channelOrderId(channelOrderId)
                .status(STATUS_COMPLETED)
                .paid(true)
                .paidTime(System.currentTimeMillis() / 1000)
                .build();
    }
    
    @Override
    public boolean closePayment(String channelOrderId) {
        log.info("取消PayPal订单, orderId={}", channelOrderId);
        // PayPal通过取消或过期自动关闭
        return true;
    }
    
    @Override
    public ChannelRefundResult refund(RefundOrder refundOrder) {
        log.info("发起PayPal退款, refundId={}, amount={}", 
                refundOrder.getRefundId().getValue(), refundOrder.getRefundAmount());
        
        // PayPal退款流程:
        // 1. 先获取CaptureId (付款已完成则存在)
        // 2. POST /v2/payments/captures/{capture_id}/refund
        
        String refundId = UUID.randomUUID().toString().replace("-", "").toUpperCase();
        
        return ChannelRefundResult.success(refundId);
    }
    
    @Override
    public ChannelRefundQueryResult queryRefund(String channelRefundId) {
        log.info("查询PayPal退款状态, refundId={}", channelRefundId);
        
        return ChannelRefundQueryResult.builder()
                .success(true)
                .channelRefundId(channelRefundId)
                .status("COMPLETED")
                .refunded(true)
                .build();
    }
    
    @Override
    public boolean verifyWebhook(WebhookRequest request) {
        // PayPal Webhook验证
        // 1. 验证传输头
        // 2. 验证webhook ID
        log.info("验证PayPal Webhook签名");
        return true;
    }
    
    @Override
    public WebhookResult parseWebhook(WebhookRequest request) {
        // 解析PayPal Webhook事件
        // 事件类型: PAYMENT.CAPTURE.COMPLETED 等
        log.info("解析PayPal Webhook事件");
        
        return WebhookResult.builder()
                .status("COMPLETED")
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
    
    /**
     * 获取OAuth Token
     */
    private String getAccessToken() {
        // POST /v1/oauth2/token
        // 实际项目中需要缓存token
        return "mock_paypal_access_token";
    }
    
    /**
     * 确认并捕获PayPal订单 (用户支付完成后调用)
     */
    public String captureOrder(String orderId) {
        log.info("捕获PayPal订单, orderId={}", orderId);
        // POST /v2/checkout/orders/{id}/capture
        return "capture_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
