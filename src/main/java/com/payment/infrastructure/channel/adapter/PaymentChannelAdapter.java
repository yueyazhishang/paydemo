package com.payment.infrastructure.channel.adapter;

import com.payment.domain.channel.model.enums.ChannelCode;
import com.payment.domain.payment.model.aggregate.PaymentOrder;
import com.payment.domain.refund.model.aggregate.RefundOrder;

import java.util.Map;

/**
 * 支付通道适配器接口 - 适配器模式
 * 
 * 定义统一的支付通道接口，所有支付通道适配器都实现此接口
 * 这是DDD中防腐层(Anti-Corruption Layer)的一部分
 * 
 * 职责:
 * 1. 将领域模型转换为通道特定请求
 * 2. 将通道响应转换为领域模型
 * 3. 隔离外部系统变化对领域模型的影响
 */
public interface PaymentChannelAdapter {
    
    /**
     * 获取支持的通道编码
     */
    ChannelCode getChannelCode();
    
    /**
     * 创建支付订单
     * 
     * @param order 领域支付订单
     * @return 通道返回的支付参数(用于前端发起支付)
     */
    ChannelCreateResult createPayment(PaymentOrder order);
    
    /**
     * 查询支付状态
     * 
     * @param channelOrderId 渠道订单号
     * @return 查询结果
     */
    ChannelQueryResult queryPayment(String channelOrderId);
    
    /**
     * 关闭支付订单
     * 
     * @param channelOrderId 渠道订单号
     * @return 是否成功
     */
    boolean closePayment(String channelOrderId);
    
    /**
     * 发起退款
     * 
     * @param refundOrder 退款订单
     * @return 退款结果
     */
    ChannelRefundResult refund(RefundOrder refundOrder);
    
    /**
     * 查询退款状态
     * 
     * @param channelRefundId 渠道退款单号
     * @return 查询结果
     */
    ChannelRefundQueryResult queryRefund(String channelRefundId);
    
    /**
     * 验证Webhook签名
     * 
     * @param request 原始请求数据
     * @return 是否验证通过
     */
    boolean verifyWebhook(WebhookRequest request);
    
    /**
     * 解析Webhook通知
     * 
     * @param request 原始请求数据
     * @return 解析结果
     */
    WebhookResult parseWebhook(WebhookRequest request);
    
    /**
     * 获取支付成功响应内容(用于返回给支付渠道)
     */
    String getSuccessResponse();
    
    /**
     * 获取支付失败响应内容
     */
    String getFailureResponse();
    
    // ========== 内部DTO ==========
    
    /**
     * 创建支付结果
     */
    class ChannelCreateResult {
        private boolean success;
        private String channelOrderId;
        private Map<String, String> paymentParams; // 支付参数，前端据此发起支付
        private String errorCode;
        private String errorMessage;
        
        public static ChannelCreateResult success(String channelOrderId, Map<String, String> paymentParams) {
            ChannelCreateResult result = new ChannelCreateResult();
            result.success = true;
            result.channelOrderId = channelOrderId;
            result.paymentParams = paymentParams;
            return result;
        }
        
        public static ChannelCreateResult failure(String errorCode, String errorMessage) {
            ChannelCreateResult result = new ChannelCreateResult();
            result.success = false;
            result.errorCode = errorCode;
            result.errorMessage = errorMessage;
            return result;
        }
        
        // Getters
        public boolean isSuccess() { return success; }
        public String getChannelOrderId() { return channelOrderId; }
        public Map<String, String> getPaymentParams() { return paymentParams; }
        public String getErrorCode() { return errorCode; }
        public String getErrorMessage() { return errorMessage; }
    }
    
    /**
     * 查询支付结果
     */
    class ChannelQueryResult {
        private boolean success;
        private String channelOrderId;
        private String status; // 通道原始状态
        private boolean paid;
        private Long paidTime;
        private String errorCode;
        private String errorMessage;
        
        // Builder pattern
        public static Builder builder() { return new Builder(); }
        
        public static class Builder {
            private ChannelQueryResult result = new ChannelQueryResult();
            
            public Builder success(boolean success) { result.success = success; return this; }
            public Builder channelOrderId(String id) { result.channelOrderId = id; return this; }
            public Builder status(String status) { result.status = status; return this; }
            public Builder paid(boolean paid) { result.paid = paid; return this; }
            public Builder paidTime(Long time) { result.paidTime = time; return this; }
            public Builder errorCode(String code) { result.errorCode = code; return this; }
            public Builder errorMessage(String msg) { result.errorMessage = msg; return this; }
            
            public ChannelQueryResult build() { return result; }
        }
        
        // Getters
        public boolean isSuccess() { return success; }
        public String getChannelOrderId() { return channelOrderId; }
        public String getStatus() { return status; }
        public boolean isPaid() { return paid; }
        public Long getPaidTime() { return paidTime; }
        public String getErrorCode() { return errorCode; }
        public String getErrorMessage() { return errorMessage; }
    }
    
    /**
     * 退款结果
     */
    class ChannelRefundResult {
        private boolean success;
        private String channelRefundId;
        private boolean refunded;
        private String errorCode;
        private String errorMessage;
        
        public static ChannelRefundResult success(String channelRefundId) {
            ChannelRefundResult result = new ChannelRefundResult();
            result.success = true;
            result.channelRefundId = channelRefundId;
            result.refunded = true;
            return result;
        }
        
        public static ChannelRefundResult failure(String errorCode, String errorMessage) {
            ChannelRefundResult result = new ChannelRefundResult();
            result.success = false;
            result.errorCode = errorCode;
            result.errorMessage = errorMessage;
            return result;
        }
        
        // Getters
        public boolean isSuccess() { return success; }
        public String getChannelRefundId() { return channelRefundId; }
        public boolean isRefunded() { return refunded; }
        public String getErrorCode() { return errorCode; }
        public String getErrorMessage() { return errorMessage; }
    }
    
    /**
     * 退款查询结果
     */
    class ChannelRefundQueryResult {
        private boolean success;
        private String channelRefundId;
        private String status;
        private boolean refunded;
        private String errorCode;
        
        public static Builder builder() { return new Builder(); }
        
        public static class Builder {
            private ChannelRefundQueryResult result = new ChannelRefundQueryResult();
            
            public Builder success(boolean success) { result.success = success; return this; }
            public Builder channelRefundId(String id) { result.channelRefundId = id; return this; }
            public Builder status(String status) { result.status = status; return this; }
            public Builder refunded(boolean refunded) { result.refunded = refunded; return this; }
            public Builder errorCode(String code) { result.errorCode = code; return this; }
            
            public ChannelRefundQueryResult build() { return result; }
        }
        
        // Getters
        public boolean isSuccess() { return success; }
        public String getChannelRefundId() { return channelRefundId; }
        public String getStatus() { return status; }
        public boolean isRefunded() { return refunded; }
        public String getErrorCode() { return errorCode; }
    }
    
    /**
     * Webhook请求
     */
    class WebhookRequest {
        private String body;
        private Map<String, String> headers;
        private String signature;
        
        public WebhookRequest(String body, Map<String, String> headers, String signature) {
            this.body = body;
            this.headers = headers;
            this.signature = signature;
        }
        
        // Getters
        public String getBody() { return body; }
        public Map<String, String> getHeaders() { return headers; }
        public String getSignature() { return signature; }
    }
    
    /**
     * Webhook解析结果
     */
    class WebhookResult {
        private String channelOrderId;
        private String channelRefundId;
        private String status;
        private boolean payment;
        private boolean refund;
        private String rawData;
        
        public static Builder builder() { return new Builder(); }
        
        public static class Builder {
            private WebhookResult result = new WebhookResult();
            
            public Builder channelOrderId(String id) { result.channelOrderId = id; return this; }
            public Builder channelRefundId(String id) { result.channelRefundId = id; return this; }
            public Builder status(String status) { result.status = status; return this; }
            public Builder payment(boolean payment) { result.payment = payment; return this; }
            public Builder refund(boolean refund) { result.refund = refund; return this; }
            public Builder rawData(String data) { result.rawData = data; return this; }
            
            public WebhookResult build() { return result; }
        }
        
        // Getters
        public String getChannelOrderId() { return channelOrderId; }
        public String getChannelRefundId() { return channelRefundId; }
        public String getStatus() { return status; }
        public boolean isPayment() { return payment; }
        public boolean isRefund() { return refund; }
        public String getRawData() { return rawData; }
    }
}
