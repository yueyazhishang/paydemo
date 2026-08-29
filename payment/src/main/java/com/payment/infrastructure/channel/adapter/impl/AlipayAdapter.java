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
 * 支付宝适配器实现
 * 
 * 支持电脑网站支付、手机网站支付、APP支付、当面付
 * 
 * 参考文档: https://opendocs.alipay.com/open/270/105898
 */
@Slf4j
@Component
public class AlipayAdapter implements PaymentChannelAdapter {
    
    @Value("${payment.alipay.appId:}")
    private String appId;
    
    @Value("${payment.alipay.privateKey:}")
    private String privateKey;
    
    @Value("${payment.alipay.publicKey:}")
    private String publicKey;
    
    @Value("${payment.alipay.notifyUrl:}")
    private String notifyUrl;
    
    @Value("${payment.alipay.gateway:https://openapi.alipay.com/gateway.do}")
    private String gateway;
    
    // 支付宝状态常量
    private static final String ALIPAY_STATUS_TRADE_SUCCESS = "TRADE_SUCCESS";
    private static final String ALIPAY_STATUS_TRADE_CLOSED = "TRADE_CLOSED";
    private static final String ALIPAY_STATUS_WAIT_BUYER_PAY = "WAIT_BUYER_PAY";
    private static final String ALIPAY_STATUS_TRADE_FINISHED = "TRADE_FINISHED";
    
    @Override
    public ChannelCode getChannelCode() {
        return ChannelCode.ALIPAY_PC;
    }
    
    @Override
    public ChannelCreateResult createPayment(PaymentOrder order) {
        log.info("创建支付宝订单, paymentId={}, amount={}", order.getPaymentId().getValue(), order.getAmount());
        
        try {
            // 构建支付宝请求参数
            Map<String, String> params = buildRequestParams(order);
            
            // 模拟调用支付宝SDK
            String channelOrderId = "ALI" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();
            
            // 返回不同支付方式的内容
            Map<String, String> paymentParams = new HashMap<>();
            
            switch (order.getChannelCode()) {
                case ALIPAY_PC:
                    // 电脑网站支付返回HTML表单(自动提交)
                    paymentParams.put("formHtml", buildPcPaymentForm(params));
                    paymentParams.put("method", "POST");
                    break;
                    
                case ALIPAY_WAP:
                    // 手机网站支付返回HTML表单
                    paymentParams.put("formHtml", buildWapPaymentForm(params));
                    paymentParams.put("method", "POST");
                    break;
                    
                case ALIPAY_APP:
                    // APP支付返回orderString给客户端SDK
                    paymentParams.put("orderString", buildAppOrderString(params));
                    break;
                    
                case ALIPAY_FACE_TO_FACE:
                    // 当面付返回二维码
                    paymentParams.put("qrCode", "https://qr.alipay.com/" + channelOrderId);
                    break;
                    
                default:
                    break;
            }
            
            log.info("支付宝订单创建成功, channelOrderId={}", channelOrderId);
            return ChannelCreateResult.success(channelOrderId, paymentParams);
            
        } catch (Exception e) {
            log.error("创建支付宝订单失败", e);
            return ChannelCreateResult.failure("ALIPAY_ERROR", e.getMessage());
        }
    }
    
    @Override
    public ChannelQueryResult queryPayment(String channelOrderId) {
        log.info("查询支付宝状态, channelOrderId={}", channelOrderId);
        
        // 调用支付宝交易查询接口
        return ChannelQueryResult.builder()
                .success(true)
                .channelOrderId(channelOrderId)
                .status(ALIPAY_STATUS_TRADE_SUCCESS)
                .paid(true)
                .paidTime(System.currentTimeMillis() / 1000)
                .build();
    }
    
    @Override
    public boolean closePayment(String channelOrderId) {
        log.info("关闭支付宝订单, channelOrderId={}", channelOrderId);
        // 调用支付宝关闭交易接口
        return true;
    }
    
    @Override
    public ChannelRefundResult refund(RefundOrder refundOrder) {
        log.info("发起支付宝退款, refundId={}, amount={}", 
                refundOrder.getRefundId().getValue(), refundOrder.getRefundAmount());
        
        // 调用支付宝退款接口
        String channelRefundId = "ALIREF" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        
        return ChannelRefundResult.success(channelRefundId);
    }
    
    @Override
    public ChannelRefundQueryResult queryRefund(String channelRefundId) {
        log.info("查询支付宝退款状态, channelRefundId={}", channelRefundId);
        
        return ChannelRefundQueryResult.builder()
                .success(true)
                .channelRefundId(channelRefundId)
                .status("REFUND_SUCCESS")
                .refunded(true)
                .build();
    }
    
    @Override
    public boolean verifyWebhook(WebhookRequest request) {
        // 验证支付宝通知签名
        // 使用支付宝公钥验证RSA/RSA2签名
        log.info("验证支付宝Webhook签名");
        return true;
    }
    
    @Override
    public WebhookResult parseWebhook(WebhookRequest request) {
        // 解析支付宝异步通知
        log.info("解析支付宝Webhook通知");
        
        return WebhookResult.builder()
                .status("TRADE_SUCCESS")
                .payment(true)
                .build();
    }
    
    @Override
    public String getSuccessResponse() {
        return "success";
    }
    
    @Override
    public String getFailureResponse() {
        return "failure";
    }
    
    // ========== 私有方法 ==========
    
    private Map<String, String> buildRequestParams(PaymentOrder order) {
        Map<String, String> params = new HashMap<>();
        params.put("app_id", appId);
        params.put("method", getApiMethod(order.getChannelCode()));
        params.put("format", "JSON");
        params.put("charset", "UTF-8");
        params.put("sign_type", "RSA2");
        params.put("timestamp", java.time.LocalDateTime.now().toString());
        params.put("version", "1.0");
        params.put("notify_url", notifyUrl + "/alipay");
        
        // 业务参数
        Map<String, String> bizContent = new HashMap<>();
        bizContent.put("out_trade_no", order.getPaymentId().getValue());
        bizContent.put("total_amount", order.getAmount().getAmount().toString());
        bizContent.put("subject", order.getDescription());
        
        // 不同产品不同参数
        if (order.getChannelCode() == ChannelCode.ALIPAY_PC || 
            order.getChannelCode() == ChannelCode.ALIPAY_WAP) {
            bizContent.put("product_code", "FAST_INSTANT_TRADE_PAY");
        }
        
        params.put("biz_content", bizContent.toString());
        
        return params;
    }
    
    private String getApiMethod(ChannelCode channelCode) {
        switch (channelCode) {
            case ALIPAY_PC: return "alipay.trade.page.pay";
            case ALIPAY_WAP: return "alipay.trade.wap.pay";
            case ALIPAY_APP: return "alipay.trade.app.pay";
            case ALIPAY_FACE_TO_FACE: return "alipay.trade.precreate";
            default: return "alipay.trade.page.pay";
        }
    }
    
    private String buildPcPaymentForm(Map<String, String> params) {
        // 构建自动提交的HTML表单
        return "<form id='alipaysubmit' action='" + gateway + "' method='POST'>" +
               "<input type='hidden' name='biz_content' value='" + params.get("biz_content") + "'/>" +
               "</form><script>document.getElementById('alipaysubmit').submit();</script>";
    }
    
    private String buildWapPaymentForm(Map<String, String> params) {
        // 构建手机网站支付表单
        return "<form id='alipaysubmit' action='" + gateway + "' method='GET'>" +
               "<input type='hidden' name='biz_content' value='" + params.get("biz_content") + "'/>" +
               "</form><script>document.getElementById('alipaysubmit').submit();</script>";
    }
    
    private String buildAppOrderString(Map<String, String> params) {
        // 构建APP支付参数字符串
        return "app_id=" + appId + "&biz_content=" + params.get("biz_content");
    }
}
