package com.example.payment.application.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/**
 * 创建支付（收单）命令。
 */
@Data
public class CreatePaymentCommand {

    /** 业务订单号（幂等键） */
    @NotBlank
    private String bizOrderNo;

    @NotBlank
    private String merchantId;

    /** 渠道枚举名，如 WECHAT_PAY / ALIPAY / JD_PAY / PAYPAL / APPLE_PAY / ANTOM / WORLDPAY / STRIPE */
    @NotBlank
    private String channel;

    /** 金额：最小货币单位（如 CNY 的分） */
    @NotNull
    @Positive
    private Long amountMinor;

    @NotBlank
    private String currency;

    @NotBlank
    private String subject;

    /** 支付结果异步通知地址（业务方接收支付结果回调），可选 */
    private String merchantNotifyUrl;

    /** JSAPI 类支付的用户标识（如微信 openId），可选 */
    private String buyerId;
}
