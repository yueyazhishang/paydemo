package com.example.payment.domain.gateway;

import com.example.payment.domain.shared.Money;
import lombok.Builder;
import lombok.Getter;

import java.util.Map;

/**
 * 统一预下单请求（防腐层统一语言）。
 * 渠道适配器负责将其翻译为渠道私有报文（微信 V3 JSON / 支付宝 RSA2 表单 / Stripe PaymentIntent...）。
 */
@Getter
@Builder
public class GatewayPayRequest {

    /** 我方支付单号（各渠道的 out_trade_no / PaymentIntentId 语义映射源） */
    private final String paymentId;

    /** 业务订单号 */
    private final String bizOrderNo;

    private final Money amount;

    /** 商品标题 */
    private final String subject;

    /** 异步通知地址（渠道回调我方的统一入口） */
    private final String notifyUrl;

    /** 支付完成后的前端跳转地址 */
    private final String returnUrl;

    /** JSAPI 类支付所需的用户标识（如微信 openId），可为空 */
    private final String buyerId;

    /** 渠道特定扩展参数（由上层透传，适配器自行解读） */
    private final Map<String, String> extra;
}
