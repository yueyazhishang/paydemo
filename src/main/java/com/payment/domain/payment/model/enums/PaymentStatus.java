package com.payment.domain.payment.model.enums;

/**
 * 支付状态枚举
 * 
 * 支付订单的生命周期状态:
 * CREATED -> PENDING -> SUCCESS/FAILED/CLOSED
 *                  |
 *                  +-> REFUNDING -> REFUNDED/PARTIAL_REFUNDED
 */
public enum PaymentStatus {
    
    /**
     * 已创建
     */
    CREATED("已创建", "支付订单已创建"),
    
    /**
     * 待支付(等待用户付款)
     */
    PENDING("待支付", "等待用户付款"),
    
    /**
     * 支付处理中
     */
    PROCESSING("处理中", "支付正在处理"),
    
    /**
     * 支付成功
     */
    SUCCESS("支付成功", "支付已完成"),
    
    /**
     * 支付失败
     */
    FAILED("支付失败", "支付失败"),
    
    /**
     * 已关闭(超时或主动关闭)
     */
    CLOSED("已关闭", "订单已关闭"),
    
    /**
     * 退款中
     */
    REFUNDING("退款中", "退款正在处理"),
    
    /**
     * 已退款
     */
    REFUNDED("已退款", "已全额退款"),
    
    /**
     * 部分退款
     */
    PARTIAL_REFUNDED("部分退款", "已部分退款"),
    
    /**
     * 已取消
     */
    CANCELLED("已取消", "订单已取消");
    
    private final String displayName;
    private final String description;
    
    PaymentStatus(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }
    
    public String getDisplayName() {
        return displayName;
    }
    
    public String getDescription() {
        return description;
    }
    
    /**
     * 判断是否为终态
     */
    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED || this == CLOSED || 
               this == REFUNDED || this == CANCELLED;
    }
    
    /**
     * 判断是否可以退款
     */
    public boolean canRefund() {
        return this == SUCCESS || this == PARTIAL_REFUNDED;
    }
    
    /**
     * 判断是否可以关闭
     */
    public boolean canClose() {
        return this == CREATED || this == PENDING;
    }
}
