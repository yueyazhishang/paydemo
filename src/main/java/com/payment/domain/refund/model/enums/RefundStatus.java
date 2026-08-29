package com.payment.domain.refund.model.enums;

/**
 * 退款状态枚举
 * 
 * 退款订单的生命周期:
 * CREATED -> PROCESSING -> SUCCESS/FAILED
 */
public enum RefundStatus {
    
    /**
     * 已创建
     */
    CREATED("已创建", "退款订单已创建"),
    
    /**
     * 处理中
     */
    PROCESSING("处理中", "退款正在处理"),
    
    /**
     * 退款成功
     */
    SUCCESS("退款成功", "退款已完成"),
    
    /**
     * 退款失败
     */
    FAILED("退款失败", "退款失败"),
    
    /**
     * 退款关闭
     */
    CLOSED("已关闭", "退款已关闭");
    
    private final String displayName;
    private final String description;
    
    RefundStatus(String displayName, String description) {
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
        return this == SUCCESS || this == FAILED || this == CLOSED;
    }
}
