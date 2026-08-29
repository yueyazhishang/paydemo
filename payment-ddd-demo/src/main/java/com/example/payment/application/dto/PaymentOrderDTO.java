package com.example.payment.application.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * 支付单结果 DTO（接口层输出契约，Published Language）。
 */
@Getter
@Builder
public class PaymentOrderDTO {

    private String paymentId;
    private String bizOrderNo;
    private String status;
    private String channel;
    private Long amountMinor;
    private String currency;
    /** 收银台类型：QR_CODE/CASHIER_URL/JSAPI/REDIRECT */
    private String payType;
    /** 收银台参数：二维码内容 / 跳转 URL / JSAPI JSON 串 */
    private String payParams;
}
