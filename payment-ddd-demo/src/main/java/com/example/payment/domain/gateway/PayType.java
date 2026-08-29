package com.example.payment.domain.gateway;

/**
 * 渠道收银台/支付要素类型（统一语义）。
 * 不同渠道返回的收银台形态不同，经防腐层统一归为以下几类：
 */
public enum PayType {

    /** 扫码支付（微信 Native、支付宝当面付等），payData 为二维码内容 */
    QR_CODE,
    /** 跳转渠道收银台（支付宝 PC/H5、Stripe Checkout、Antom 收银台等），payData 为跳转 URL */
    CASHIER_URL,
    /** JSAPI/小程序拉起支付，payData 为前端调起所需的 JSON 串 */
    JSAPI,
    /** 跳转类支付（PayPal、Worldpay 重定向等），payData 为 redirect URL */
    REDIRECT
}
