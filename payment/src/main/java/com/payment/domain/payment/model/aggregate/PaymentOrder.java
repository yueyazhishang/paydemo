package com.payment.domain.payment.model.aggregate;

import com.payment.domain.channel.model.enums.ChannelCode;
import com.payment.domain.payment.model.entity.PaymentTransaction;
import com.payment.domain.payment.model.enums.PaymentStatus;
import com.payment.domain.payment.model.valueobject.Money;
import com.payment.domain.payment.model.valueobject.OrderId;
import com.payment.domain.payment.model.valueobject.PaymentId;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 支付订单聚合根
 * 
 * 聚合根职责:
 * 1. 维护支付订单的完整生命周期
 * 2. 保证业务规则和数据一致性
 * 3. 封装领域逻辑
 * 
 * 聚合边界:
 * - PaymentOrder (聚合根)
 * - PaymentTransaction (实体，交易记录)
 */
@Getter
public class PaymentOrder {
    
    /**
     * 支付订单ID
     */
    private PaymentId paymentId;
    
    /**
     * 商户订单ID(业务方订单号)
     */
    private OrderId merchantOrderId;
    
    /**
     * 商户ID
     */
    private String merchantId;
    
    /**
     * 用户ID
     */
    private String userId;
    
    /**
     * 订单金额
     */
    private Money amount;
    
    /**
     * 已退款金额
     */
    private Money refundedAmount;
    
    /**
     * 订单描述
     */
    private String description;
    
    /**
     * 支付状态
     */
    private PaymentStatus status;
    
    /**
     * 当前使用的支付通道
     */
    private ChannelCode channelCode;
    
    /**
     * 异步通知URL
     */
    private String notifyUrl;
    
    /**
     * 返回URL(支付成功后跳转)
     */
    private String returnUrl;
    
    /**
     * 过期时间
     */
    private LocalDateTime expireTime;
    
    /**
     * 交易记录列表
     */
    private List<PaymentTransaction> transactions;
    
    /**
     * 扩展参数(各通道特有参数)
     */
    private Map<String, String> extraParams;
    
    /**
     * 创建时间
     */
    private LocalDateTime createdAt;
    
    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
    
    /**
     * 成功时间
     */
    private LocalDateTime successAt;
    
    /**
     * 版本号(乐观锁)
     */
    private Long version;
    
    // ========== 构造方法 ==========
    
    @Builder
    public PaymentOrder(PaymentId paymentId, OrderId merchantOrderId, String merchantId, String userId,
                        Money amount, String description, ChannelCode channelCode,
                        String notifyUrl, String returnUrl, LocalDateTime expireTime,
                        Map<String, String> extraParams) {
        // 参数验证
        Objects.requireNonNull(paymentId, "支付ID不能为空");
        Objects.requireNonNull(merchantOrderId, "商户订单ID不能为空");
        Objects.requireNonNull(amount, "金额不能为空");
        if (amount.isZero() || !amount.isPositive()) {
            throw new IllegalArgumentException("支付金额必须大于0");
        }
        
        this.paymentId = paymentId;
        this.merchantOrderId = merchantOrderId;
        this.merchantId = merchantId;
        this.userId = userId;
        this.amount = amount;
        this.refundedAmount = Money.of(0, amount.getCurrency());
        this.description = description;
        this.status = PaymentStatus.CREATED;
        this.channelCode = channelCode;
        this.notifyUrl = notifyUrl;
        this.returnUrl = returnUrl;
        this.expireTime = expireTime != null ? expireTime : LocalDateTime.now().plusMinutes(30);
        this.transactions = new ArrayList<>();
        this.extraParams = extraParams != null ? new HashMap<>(extraParams) : new HashMap<>();
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
        this.version = 0L;
    }
    
    // ========== 领域行为 ==========
    
    /**
     * 发起支付
     * 创建初始交易记录，将订单状态变为待支付
     */
    public PaymentTransaction initiatePayment(ChannelCode channel) {
        if (this.status != PaymentStatus.CREATED) {
            throw new IllegalStateException("只有已创建的订单才能发起支付，当前状态: " + this.status);
        }
        
        this.channelCode = channel;
        this.status = PaymentStatus.PENDING;
        this.updatedAt = LocalDateTime.now();
        
        PaymentTransaction transaction = PaymentTransaction.builder()
                .transactionId(UUID.randomUUID().toString())
                .paymentId(this.paymentId)
                .channelCode(channel)
                .amount(this.amount)
                .status(PaymentStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .build();
        
        this.transactions.add(transaction);
        return transaction;
    }
    
    /**
     * 支付成功处理
     * @param channelOrderId 渠道订单号
     * @param transaction 交易记录
     */
    public void processPaymentSuccess(String channelOrderId, PaymentTransaction transaction) {
        if (this.status != PaymentStatus.PENDING && this.status != PaymentStatus.PROCESSING) {
            throw new IllegalStateException("只有待支付或处理中的订单才能完成支付，当前状态: " + this.status);
        }
        
        transaction.markSuccess(channelOrderId);
        this.status = PaymentStatus.SUCCESS;
        this.successAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }
    
    /**
     * 支付失败处理
     * @param reason 失败原因
     * @param transaction 交易记录
     */
    public void processPaymentFailure(String reason, PaymentTransaction transaction) {
        transaction.markFailed(reason);
        this.status = PaymentStatus.FAILED;
        this.updatedAt = LocalDateTime.now();
    }
    
    /**
     * 关闭订单
     * @param reason 关闭原因
     */
    public void close(String reason) {
        if (!this.status.canClose()) {
            throw new IllegalStateException("当前状态不允许关闭: " + this.status);
        }
        
        this.status = PaymentStatus.CLOSED;
        this.updatedAt = LocalDateTime.now();
        
        // 关闭当前处理的交易
        getCurrentTransaction().ifPresent(t -> t.markClosed(reason));
    }
    
    /**
     * 检查订单是否已过期
     */
    public boolean isExpired() {
        return LocalDateTime.now().isAfter(this.expireTime);
    }
    
    /**
     * 处理过期
     */
    public void processExpired() {
        if (this.status == PaymentStatus.PENDING && isExpired()) {
            this.status = PaymentStatus.CLOSED;
            this.updatedAt = LocalDateTime.now();
        }
    }
    
    /**
     * 添加退款金额
     */
    public void addRefundedAmount(Money refundAmount) {
        if (!this.status.canRefund()) {
            throw new IllegalStateException("当前状态不允许退款: " + this.status);
        }
        
        Money newRefundedAmount = this.refundedAmount.add(refundAmount);
        if (newRefundedAmount.isGreaterThan(this.amount)) {
            throw new IllegalArgumentException("退款金额不能超过订单金额");
        }
        
        this.refundedAmount = newRefundedAmount;
        
        // 更新退款状态
        if (newRefundedAmount.isZero()) {
            // 无退款
        } else if (newRefundedAmount.amount.compareTo(this.amount.amount) == 0) {
            this.status = PaymentStatus.REFUNDED;
        } else {
            this.status = PaymentStatus.PARTIAL_REFUNDED;
        }
        
        this.updatedAt = LocalDateTime.now();
    }
    
    /**
     * 获取可退款金额
     */
    public Money getRefundableAmount() {
        return this.amount.subtract(this.refundedAmount);
    }
    
    /**
     * 获取当前交易记录
     */
    public Optional<PaymentTransaction> getCurrentTransaction() {
        if (transactions.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(transactions.get(transactions.size() - 1));
    }
    
    /**
     * 获取所有交易记录
     */
    public List<PaymentTransaction> getTransactions() {
        return Collections.unmodifiableList(transactions);
    }
    
    /**
     * 添加交易记录
     */
    public void addTransaction(PaymentTransaction transaction) {
        this.transactions.add(transaction);
    }
    
    /**
     * 获取扩展参数
     */
    public String getExtraParam(String key) {
        return extraParams.get(key);
    }
    
    /**
     * 设置扩展参数
     */
    public void setExtraParam(String key, String value) {
        this.extraParams.put(key, value);
        this.updatedAt = LocalDateTime.now();
    }
}
