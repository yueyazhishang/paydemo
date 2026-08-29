package com.payment.domain.payment.model.valueobject;

import lombok.Value;

import java.util.UUID;

/**
 * 订单ID值对象
 * 
 * 封装订单唯一标识，提供类型安全
 */
@Value
public class OrderId {
    
    String value;
    
    /**
     * 生成新的订单ID
     */
    public static OrderId generate() {
        return new OrderId("ORD_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase());
    }
    
    /**
     * 从已有值创建
     */
    public static OrderId of(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("订单ID不能为空");
        }
        return new OrderId(value);
    }
}
