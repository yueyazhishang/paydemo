package com.payment.domain.refund.model.aggregate;

import com.payment.domain.channel.model.enums.ChannelCode;
import com.payment.domain.payment.model.valueobject.Money;
import com.payment.domain.payment.model.valueobject.PaymentId;
import com.payment.domain.payment.model.valueobject.RefundId;
import com.payment.domain.refund.model.enums.RefundStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 退款订单聚合根
 * 
 * 管理退款的全生命周期
 */
@Getter
public class RefundOrder {
    
    /**
     * 退款订单ID
     */
    private RefundId refundId;
    
    /**
     * 原支付订单ID
     */
    private PaymentId paymentId;
    
    /**
     * 商户ID
     */
    private String merchantId;
    
    /**
     * 退款金额
     */
    private Money refundAmount;
    
    /**
     * 退款原因
     */
    private String reason;
    
    /**
     * 退款状态
     */
    private RefundStatus status;
    
    /**
     * 支付通道
     */
    private ChannelCode channelCode;
    
    /**
     * 渠道退款单号
     */
    private String channelRefundId;
    
    /**
     * 退款完成时间
     */
    private LocalDateTime completedAt;
    
    /**
     * 错误信息
     */
    private String errorMessage;
    
    /**
     * 回调URL
     */
    private String notifyUrl;
    
    /**
     * 扩展参数
     */
    private String extraParams;
    
    /**
     * 创建时间
     */
    private LocalDateTime createdAt;
    
    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
    
    @Builder
    public RefundOrder(RefundId refundId, PaymentId paymentId, String merchantId,
                       Money refundAmount, String reason, ChannelCode channelCode,
                       String notifyUrl, String extraParams) {
        Objects.requireNonNull(refundId, "退款ID不能为空");
        Objects.requireNonNull(paymentId, "支付订单ID不能为空");
        Objects.requireNonNull(refundAmount, "退款金额不能为空");
        if (refundAmount.isZero() || !refundAmount.isPositive()) {
            throw new IllegalArgumentException("退款金额必须大于0");
        }
        
        this.refundId = refundId;
        this.paymentId = paymentId;
        this.merchantId = merchantId;
        this.refundAmount = refundAmount;
        this.reason = reason;
        this.status = RefundStatus.CREATED;
        this.channelCode = channelCode;
        this.notifyUrl = notifyUrl;
        this.extraParams = extraParams;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }
    
    /**
     * 开始退款处理
     */
    public void startProcessing() {
        if (this.status != RefundStatus.CREATED) {
            throw new IllegalStateException("只有已创建的退款单才能开始处理，当前状态: " + this.status);
        }
        this.status = RefundStatus.PROCESSING;
        this.updatedAt = LocalDateTime.now();
    }
    
    /**
     * 退款成功
     */
    public void markSuccess(String channelRefundId) {
        if (this.status != RefundStatus.PROCESSING && this.status != RefundStatus.CREATED) {
            throw new IllegalStateException("当前状态不允许标记成功: " + this.status);
        }
        this.status = RefundStatus.SUCCESS;
        this.channelRefundId = channelRefundId;
        this.completedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }
    
    /**
     * 退款失败
     */
    public void markFailed(String errorMessage) {
        if (this.status != RefundStatus.PROCESSING) {
            throw new IllegalStateException("当前状态不允许标记失败: " + this.status);
        }
        this.status = RefundStatus.FAILED;
        this.errorMessage = errorMessage;
        this.completedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }
    
    /**
     * 关闭退款
     */
    public void close(String reason) {
        if (this.status.isTerminal()) {
            throw new IllegalStateException("当前状态已终态，不能关闭: " + this.status);
        }
        this.status = RefundStatus.CLOSED;
        this.errorMessage = reason;
        this.updatedAt = LocalDateTime.now();
    }
    
    /**
     * 判断是否可重试
     */
    public boolean canRetry() {
        return this.status == RefundStatus.FAILED;
    }
    
    /**
     * 重试退款
     */
    public void retry() {
        if (!canRetry()) {
            throw new IllegalStateException("当前状态不允许重试: " + this.status);
        }
        this.status = RefundStatus.CREATED;
        this.errorMessage = null;
        this.updatedAt = LocalDateTime.now();
    }
}
