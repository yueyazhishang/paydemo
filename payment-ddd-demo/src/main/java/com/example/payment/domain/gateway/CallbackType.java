package com.example.payment.domain.gateway;

/**
 * 回调类型。
 */
public enum CallbackType {

    /** 支付结果通知 */
    PAYMENT,

    /** 退款结果通知（微信等异步退款渠道） */
    REFUND
}
