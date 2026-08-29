package com.payment.domain.payment.model.valueobject;

import lombok.Value;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 退款ID值对象
 */
@Value
public class RefundId {
    
    String value;
    
    private static final AtomicLong SEQUENCE = new AtomicLong(0);
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    
    /**
     * 生成新的退款ID
     */
    public static RefundId generate() {
        String timestamp = LocalDateTime.now().format(DATE_FORMAT);
        long seq = SEQUENCE.incrementAndGet() % 1000000;
        return new RefundId(String.format("REF%s%06d", timestamp, seq));
    }
    
    /**
     * 从已有值创建
     */
    public static RefundId of(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("退款ID不能为空");
        }
        return new RefundId(value);
    }
}
