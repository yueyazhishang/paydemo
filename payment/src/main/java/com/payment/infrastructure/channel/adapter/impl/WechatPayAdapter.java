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
 * 微信支付适配器实现
 * 
 * 支持JSAPI/Native/H5/APP/小程序等多种支付方式
 * 
 * 参考文档: https://pay.weixin.qq.com/wiki/doc/api/index.html
 */
@Slf4j
@Component
public class WechatPayAdapter implements PaymentChannelAdapter {
    
    @Value("${payment.wechat.appId:}")
    private String appId;
    
    @Value("${payment.wechat.mchId:}")
    private String mchId;
    
    @Value("${payment.wechat.apiKey:}")
    private String apiKey;
    
    @Value("${payment.wechat.notifyUrl:}")
    private String notifyUrl;
    
    // 微信支付状态常量
    private static final String WECHAT_STATUS_SUCCESS = "SUCCESS";
    private static final String WECHAT_STATUS_NOTPAY = "NOTPAY";
    private static final String WECHAT_STATUS_CLOSED = "CLOSED";
    private static final String WECHAT_STATUS_REFUND = "REFUND";
    
    @Override
    public ChannelCode getChannelCode() {
        return ChannelCode.WECHAT_JSAPI;
    }
    
    @Override
    public ChannelCreateResult createPayment(PaymentOrder order) {
        log.info("创建微信支付订单, paymentId={}, amount={}", order.getPaymentId().getValue(), order.getAmount());
        
        try {
            // 构建微信支付请求参数
            Map<String, String> requestParams = buildUnifiedOrderRequest(order);
            
            // 1. 调用微信统一下单API (实际项目中这里调用微信SDK)
            // 模拟调用微信API
            String channelOrderId = "WX" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();
            
            // 2. 根据支付方式生成不同的前端支付参数
            Map<String, String> paymentParams = generatePaymentParams(order, channelOrderId);
            
            log.info("微信支付订单创建成功, channelOrderId={}", channelOrderId);
            return ChannelCreateResult.success(channelOrderId, paymentParams);
            
        } catch (Exception e) {
            log.error("创建微信支付订单失败", e);
            return ChannelCreateResult.failure("WECHAT_ERROR", e.getMessage());
        }
    }
    
    @Override
    public ChannelQueryResult queryPayment(String channelOrderId) {
        log.info("查询微信支付状态, channelOrderId={}", channelOrderId);
        
        // 实际项目中调用微信订单查询API
        // 模拟返回
        return ChannelQueryResult.builder()
                .success(true)
                .channelOrderId(channelOrderId)
                .status(WECHAT_STATUS_SUCCESS)
                .paid(true)
                .paidTime(System.currentTimeMillis() / 1000)
                .build();
    }
    
    @Override
    public boolean closePayment(String channelOrderId) {
        log.info("关闭微信支付订单, channelOrderId={}", channelOrderId);
        // 调用微信关单API
        return true;
    }
    
    @Override
    public ChannelRefundResult refund(RefundOrder refundOrder) {
        log.info("发起微信退款, refundId={}, amount={}", 
                refundOrder.getRefundId().getValue(), refundOrder.getRefundAmount());
        
        // 实际项目中调用微信退款API
        // 需要使用证书进行双向认证
        String channelRefundId = "WXREF" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        
        return ChannelRefundResult.success(channelRefundId);
    }
    
    @Override
    public ChannelRefundQueryResult queryRefund(String channelRefundId) {
        log.info("查询微信退款状态, channelRefundId={}", channelRefundId);
        
        return ChannelRefundQueryResult.builder()
                .success(true)
                .channelRefundId(channelRefundId)
                .status("SUCCESS")
                .refunded(true)
                .build();
    }
    
    @Override
    public boolean verifyWebhook(WebhookRequest request) {
        // 验证微信通知签名
        // 1. 获取通知中的timestamp, nonce, signature
        // 2. 使用API Key生成签名并比较
        log.info("验证微信Webhook签名");
        return true;
    }
    
    @Override
    public WebhookResult parseWebhook(WebhookRequest request) {
        // 解析微信异步通知
        // 实际项目中使用微信SDK解析XML/JSON通知
        log.info("解析微信Webhook通知");
        
        return WebhookResult.builder()
                .status("SUCCESS")
                .payment(true)
                .build();
    }
    
    @Override
    public String getSuccessResponse() {
        // 返回微信要求格式: <xml><return_code>SUCCESS</return_code></xml>
        return "<xml><return_code><![CDATA[SUCCESS]]></return_code></xml>";
    }
    
    @Override
    public String getFailureResponse() {
        return "<xml><return_code><![CDATA[FAIL]]></return_code></xml>";
    }
    
    // ========== 私有方法 ==========
    
    /**
     * 构建统一下单请求参数
     */
    private Map<String, String> buildUnifiedOrderRequest(PaymentOrder order) {
        Map<String, String> params = new HashMap<>();
        params.put("appid", appId);
        params.put("mch_id", mchId);
        params.put("nonce_str", UUID.randomUUID().toString().replace("-", ""));
        params.put("body", order.getDescription());
        params.put("out_trade_no", order.getPaymentId().getValue());
        // 微信以分为单位
        params.put("total_fee", String.valueOf(order.getAmount().toCents()));
        params.put("spbill_create_ip", "127.0.0.1");
        params.put("notify_url", notifyUrl + "/wechat");
        params.put("trade_type", getTradeType(order.getChannelCode()));
        
        // JSAPI需要openid
        if (order.getChannelCode() == ChannelCode.WECHAT_JSAPI) {
            String openid = order.getExtraParam("openid");
            if (openid != null) {
                params.put("openid", openid);
            }
        }
        
        // 生成签名
        params.put("sign", generateSign(params));
        
        return params;
    }
    
    /**
     * 生成前端支付参数
     */
    private Map<String, String> generatePaymentParams(PaymentOrder order, String prepayId) {
        Map<String, String> paymentParams = new HashMap<>();
        
        switch (order.getChannelCode()) {
            case WECHAT_JSAPI:
                // JSAPI需要返回prepay_id给前端，前端用wx.chooseWXPay发起支付
                paymentParams.put("appId", appId);
                paymentParams.put("timeStamp", String.valueOf(System.currentTimeMillis() / 1000));
                paymentParams.put("nonceStr", UUID.randomUUID().toString().replace("-", ""));
                paymentParams.put("package", "prepay_id=" + prepayId);
                paymentParams.put("signType", "RSA");
                paymentParams.put("paySign", "mock_sign");
                break;
                
            case WECHAT_NATIVE:
                // Native模式返回二维码链接
                paymentParams.put("code_url", "weixin://wxpay/bizpayurl?pr=" + prepayId);
                break;
                
            case WECHAT_H5:
                // H5返回跳转链接
                paymentParams.put("mweb_url", "https://wx.tenpay.com/cgi-bin/mmpayweb-bin/checkmweb?prepay_id=" + prepayId);
                break;
                
            case WECHAT_APP:
                // APP支付参数
                paymentParams.put("appid", appId);
                paymentParams.put("partnerid", mchId);
                paymentParams.put("prepayid", prepayId);
                paymentParams.put("package", "Sign=WXPay");
                paymentParams.put("noncestr", UUID.randomUUID().toString().replace("-", ""));
                paymentParams.put("timestamp", String.valueOf(System.currentTimeMillis() / 1000));
                paymentParams.put("sign", "mock_sign");
                break;
                
            default:
                break;
        }
        
        return paymentParams;
    }
    
    /**
     * 获取交易类型
     */
    private String getTradeType(ChannelCode channelCode) {
        switch (channelCode) {
            case WECHAT_JSAPI: return "JSAPI";
            case WECHAT_NATIVE: return "NATIVE";
            case WECHAT_H5: return "MWEB";
            case WECHAT_APP: return "APP";
            case WECHAT_MINI: return "JSAPI";
            default: return "JSAPI";
        }
    }
    
    /**
     * 生成签名
     */
    private String generateSign(Map<String, String> params) {
        // 实际项目中使用微信支付签名算法
        // 1. 参数排序
        // 2. 拼接成字符串
        // 3. MD5加密
        return "mock_wechat_sign";
    }
}
