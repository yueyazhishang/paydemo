package com.payment.domain.payment.model.entity;

import com.payment.domain.channel.model.enums.ChannelCode;
import com.payment.domain.payment.model.enums.PaymentStatus;
import com.payment.domain.payment.model.valueobject.Money;
import com.payment.domain.payment.model.valueobject.PaymentId;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 支付交易实体
 * 
 * 记录与第三方支付通道交互的每次交易
 * 一个支付订单可能有多笔交易(如第一次失败后重试其他通道)
 */
@Getter
@Builder
public class PaymentTransaction {
    
    /**
     * 交易ID
     */
    private String transactionId;
    
    /**
     * 关联的支付订单ID
     */
    private PaymentId paymentId;
    
    /**
     * 使用的支付通道
     */
    private ChannelCode channelCode;
    
    /**
     * 交易金额
     */
    private Money amount;
    
    /**
     * 交易状态
     */
    private PaymentStatus status;
    
    /**
     * 渠道订单号
     */
    private String channelOrderId;
    
    /**
     * 渠道返回的支付参数(用于前端发起支付)
     */
    private Map<String, String> channelPaymentParams;
    
    /**
     * 渠道返回的原始数据
     */
    private String channelRawData;
    
    /**
     * 错误信息(如果失败)
     */
    private String errorMessage;
    
    /**
     * 交易创建时间
     */
    private LocalDateTime createdAt;
    
    /**
     * 交易完成时间
     */
    private LocalDateTime completedAt;
    
    /**
     * 渠道通知时间
     */
    private LocalDateTime notifiedAt;
    
    /**
     * 标记交易成功
     */
    public void markSuccess(String channelOrderId) {
        this.status = PaymentStatus.SUCCESS;
        this.channelOrderId = channelOrderId;
        this.completedAt = LocalDateTime.now();
    }
    
    /**
     * 标记交易失败
     */
    public void markFailed(String errorMessage) {
        this.status = PaymentStatus.FAILED;
        this.errorMessage = errorMessage;
        this.completedAt = LocalDateTime.now();
    }
    
    /**
     * 标记交易关闭
     */
    public void markClosed(String reason) {
        this.status = PaymentStatus.CLOSED;
        this.errorMessage = reason;
        this.completedAt = LocalDateTime.now();
    }
}
