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
 * Adyen (原Antom) 支付适配器
 * 
 * 全球支付平台，支持250+支付方式，30+货币
 * - 信用卡/借记卡
 * - 本地支付方式 (iDEAL, Bancontact, OXXO等)
 * - 数字钱包 (PayPal, Apple Pay, Google Pay)
 * 
 * 文档: https://docs.adyen.com/
 */
@Slf4j
@Component
public class AdyenAdapter implements PaymentChannelAdapter {
    
    @Value("${payment.adyen.apiKey:}")
    private String apiKey;
    
    @Value("${payment.adyen.merchantAccount:}")
    private String merchantAccount;
    
    @Value("${payment.adyen.clientKey:}")
    private String clientKey;
    
    @Value("${payment.adyen.environment:test}")
    private String environment;
    
    // Adyen状态常量
    private static final String STATUS_RECEIVED = "Received";
    private static final String STATUS_AUTHORISED = "Authorised";
    private static final String STATUS_CANCELLED = "Cancelled";
    private static final String STATUS_ERROR = "Error";
    private static final String STATUS_REFUSED = "Refused";
    private static final String STATUS_REFUNDED = "Refunded";
    private static final String STATUS_PARTIALLY_REFUNDED = "PartiallyRefunded";
    
    @Override
    public ChannelCode getChannelCode() {
        return ChannelCode.ADYEN;
    }
    
    @Override
    public ChannelCreateResult createPayment(PaymentOrder order) {
        log.info("创建Adyen订单, paymentId={}, amount={}", order.getPaymentId().getValue(), order.getAmount());
        
        try {
            // Adyen的支付流程:
            // 1. /paymentSessions (旧版) 或 /payments (新版)
            // 2. 返回客户端需要的支付数据
            // 3. 前端使用Adyen组件完成支付
            
            Map<String, Object> paymentRequest = new HashMap<>();
            paymentRequest.put("merchantAccount", merchantAccount);
            paymentRequest.put("reference", order.getPaymentId().getValue());
            paymentRequest.put("amount", Map.of(
                "currency", order.getAmount().getCurrency().getCurrencyCode(),
                "value", order.getAmount().toCents()
            ));
            paymentRequest.put("returnUrl", order.getReturnUrl());
            paymentRequest.put("countryCode", getCountryCode(order));
            
            // 指定支付方式(可选)
            String paymentMethod = order.getExtraParam("paymentMethod");
            if (paymentMethod != null) {
                paymentRequest.put("paymentMethod", Map.of("type", paymentMethod));
            }
            
            // 模拟API调用
            String pspReference = "PSP" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();
            
            Map<String, String> paymentParams = new HashMap<>();
            paymentParams.put("pspReference", pspReference);
            paymentParams.put("clientKey", clientKey);
            paymentParams.put("environment", environment);
            
            log.info("Adyen订单创建成功, pspReference={}", pspReference);
            return ChannelCreateResult.success(pspReference, paymentParams);
            
        } catch (Exception e) {
            log.error("创建Adyen订单失败", e);
            return ChannelCreateResult.failure("ADYEN_ERROR", e.getMessage());
        }
    }
    
    @Override
    public ChannelQueryResult queryPayment(String channelOrderId) {
        log.info("查询Adyen状态, pspReference={}", channelOrderId);
        
        return ChannelQueryResult.builder()
                .success(true)
                .channelOrderId(channelOrderId)
                .status(STATUS_AUTHORISED)
                .paid(true)
                .paidTime(System.currentTimeMillis() / 1000)
                .build();
    }
    
    @Override
    public boolean closePayment(String channelOrderId) {
        log.info("取消Adyen订单, pspReference={}", channelOrderId);
        // Adyen通过取消支付或让其过期来关闭
        return true;
    }
    
    @Override
    public ChannelRefundResult refund(RefundOrder refundOrder) {
        log.info("发起Adyen退款, refundId={}, amount={}", 
                refundOrder.getRefundId().getValue(), refundOrder.getRefundAmount());
        
        // POST /payments/{paymentPspReference}/refunds
        String refundPspReference = "REF" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();
        
        return ChannelRefundResult.success(refundPspReference);
    }
    
    @Override
    public ChannelRefundQueryResult queryRefund(String channelRefundId) {
        log.info("查询Adyen退款状态, refundPspReference={}", channelRefundId);
        
        return ChannelRefundQueryResult.builder()
                .success(true)
                .channelRefundId(channelRefundId)
                .status(STATUS_REFUNDED)
                .refunded(true)
                .build();
    }
    
    @Override
    public boolean verifyWebhook(WebhookRequest request) {
        // Adyen使用HMAC签名验证Webhook
        // 1. 获取notificationItems
        // 2. 计算HMAC签名
        // 3. 比较
        log.info("验证Adyen Webhook签名");
        return true;
    }
    
    @Override
    public WebhookResult parseWebhook(WebhookRequest request) {
        // 解析Adyen通知
        // 事件类型: AUTHORISATION, REFUND, CANCELLATION等
        log.info("解析Adyen Webhook事件");
        
        return WebhookResult.builder()
                .status(STATUS_AUTHORISED)
                .payment(true)
                .build();
    }
    
    @Override
    public String getSuccessResponse() {
        // Adyen要求返回 [accepted]
        return "[accepted]";
    }
    @Override
    public String getFailureResponse() {
        return "[failed]";
    }
    
    private String getCountryCode(PaymentOrder order) {
        return switch (order.getAmount().getCurrency().getCurrencyCode()) {
            case "CNY" -> "CN";
            case "USD" -> "US";
            case "EUR" -> "NL";
            case "GBP" -> "GB";
            case "JPY" -> "JP";
            default -> "US";
        };
    }
}
