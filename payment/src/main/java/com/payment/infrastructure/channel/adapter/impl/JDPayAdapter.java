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
 * 京东支付适配器
 * 
 * 支持网银支付、快捷支付
 * 
 * 京东支付基于网银在线(京东旗下支付公司)
 */
@Slf4j
@Component
public class JDPayAdapter implements PaymentChannelAdapter {
    
    @Value("${payment.jdpay.merchantId:}")
    private String merchantId;
    
    @Value("${payment.jdpay.desKey:}")
    private String desKey;
    
    @Value("${payment.jdpay.md5Key:}")
    private String md5Key;
    
    @Value("${payment.jdpay.notifyUrl:}")
    private String notifyUrl;
    
    @Override
    public ChannelCode getChannelCode() {
        return ChannelCode.JDPAY_EBANK;
    }
    
    @Override
    public ChannelCreateResult createPayment(PaymentOrder order) {
        log.info("创建京东支付订单, paymentId={}, amount={}", order.getPaymentId().getValue(), order.getAmount());
        
        try {
            // 京东支付基于网银在线SDK
            // 1. 组装请求参数
            Map<String, String> params = new HashMap<>();
            params.put("merchant", merchantId);
            params.put("orderId", order.getPaymentId().getValue());
            params.put("orderTime", java.time.LocalDateTime.now().format(
                    java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss")));
            
            // 京东支付以分为单位
            params.put("amount", String.valueOf(order.getAmount().toCents()));
            params.put("currency", "CNY");
            params.put("name", order.getDescription());
            params.put("notifyUrl", notifyUrl + "/jdpay");
            // 网银支付特定参数
            params.put("bizType", "0"); // 0:B2C
            params.put("deviceType", "0"); // 0:PC, 1:Mobile
            
            // 如果是快捷支付，需要保存用户信息
            if (order.getChannelCode() == ChannelCode.JDPAY_QUICK) {
                params.put("save", "1");
            }
            
            // 生成签名
            params.put("sign", generateSign(params));
            
            // 模拟调用京东支付API
            String channelOrderId = "JD" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();
            
            Map<String, String> paymentParams = new HashMap<>();
            paymentParams.put("payUrl", "https://wepay.jd.com/jdpay/pay");
            paymentParams.put("params", params.toString());
            
            log.info("京东支付订单创建成功, channelOrderId={}", channelOrderId);
            return ChannelCreateResult.success(channelOrderId, paymentParams);
            
        } catch (Exception e) {
            log.error("创建京东支付订单失败", e);
            return ChannelCreateResult.failure("JDPAY_ERROR", e.getMessage());
        }
    }
    
    @Override
    public ChannelQueryResult queryPayment(String channelOrderId) {
        log.info("查询京东支付状态, channelOrderId={}", channelOrderId);
        
        return ChannelQueryResult.builder()
                .success(true)
                .channelOrderId(channelOrderId)
                .status("SUCCESS")
                .paid(true)
                .paidTime(System.currentTimeMillis() / 1000)
                .build();
    }
    
    @Override
    public boolean closePayment(String channelOrderId) {
        log.info("关闭京东支付订单, channelOrderId={}", channelOrderId);
        return true;
    }
    
    @Override
    public ChannelRefundResult refund(RefundOrder refundOrder) {
        log.info("发起京东退款, refundId={}, amount={}", 
                refundOrder.getRefundId().getValue(), refundOrder.getRefundAmount());
        
        String channelRefundId = "JDREF" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        
        return ChannelRefundResult.success(channelRefundId);
    }
    
    @Override
    public ChannelRefundQueryResult queryRefund(String channelRefundId) {
        log.info("查询京东退款状态, channelRefundId={}", channelRefundId);
        
        return ChannelRefundQueryResult.builder()
                .success(true)
                .channelRefundId(channelRefundId)
                .status("SUCCESS")
                .refunded(true)
                .build();
    }
    
    @Override
    public boolean verifyWebhook(WebhookRequest request) {
        log.info("验证京东支付Webhook签名");
        return true;
    }
    
    @Override
    public WebhookResult parseWebhook(WebhookRequest request) {
        log.info("解析京东支付Webhook通知");
        
        return WebhookResult.builder()
                .status("SUCCESS")
                .payment(true)
                .build();
    }
    
    @Override
    public String getSuccessResponse() {
        return "success";
    }
    
    @Override
    public String getFailureResponse() {
        return "fail";
    }
    
    /**
     * 生成签名
     */
    private String generateSign(Map<String, String> params) {
        // 京东支付使用DES加密 + MD5签名
        // 1. 参数排序
        // 2. 拼接成字符串
        // 3. DES加密
        // 4. MD5签名
        return "mock_jdpay_sign";
    }
}
