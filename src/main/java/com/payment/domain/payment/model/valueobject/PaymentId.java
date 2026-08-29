package com.payment.domain.payment.model.valueobject;

import lombok.Value;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 支付ID值对象
 * 
 * 全局唯一支付订单号
 */
@Value
public class PaymentId {
    
    String value;
    
    private static final AtomicLong SEQUENCE = new AtomicLong(0);
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    
    /**
     * 生成新的支付ID
     * 格式: PAY + 时间戳 + 6位序列号
     */
    public static PaymentId generate() {
        String timestamp = LocalDateTime.now().format(DATE_FORMAT);
        long seq = SEQUENCE.incrementAndGet() % 1000000;
        return new PaymentId(String.format("PAY%s%06d", timestamp, seq));
    }
    
    /**
     * 从已有值创建
     */
    public static PaymentId of(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("支付ID不能为空");
        }
        return new PaymentId(value);
    }
}
