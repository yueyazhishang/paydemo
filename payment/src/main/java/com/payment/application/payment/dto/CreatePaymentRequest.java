package com.payment.application.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 创建支付请求DTO
 * 
 * 应用层DTO，用于接口层与 application 层之间的数据传输
 */
@Data
public class CreatePaymentRequest {
    
    /**
     * 商户ID
     */
    @NotBlank(message = "商户ID不能为空")
    private String merchantId;
    
    /**
     * 商户订单号
     */
    @NotBlank(message = "订单号不能为空")
    private String orderId;
    
    /**
     * 用户ID
     */
    private String userId;
    
    /**
     * 支付金额
     */
    @NotNull(message = "金额不能为空")
    @Positive(message = "金额必须大于0")
    private BigDecimal amount;
    
    /**
     * 货币代码 (CNY, USD, EUR等)
     */
    @NotBlank(message = "货币不能为空")
    private String currency;
    
    /**
     * 支付通道编码
     */
    @NotBlank(message = "支付通道不能为空")
    private String channelCode;
    
    /**
     * 订单描述
     */
    private String description;
    
    /**
     * 异步通知URL
     */
    private String notifyUrl;
    
    /**
     * 返回URL
     */
    private String returnUrl;
    
    /**
     * 扩展参数(各通道特有)
     */
    private Map<String, String> extra;
}
