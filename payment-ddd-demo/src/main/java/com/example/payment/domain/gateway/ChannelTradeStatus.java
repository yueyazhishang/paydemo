package com.example.payment.domain.gateway;

/**
 * 渠道侧交易状态（查单兜底用，统一语义）。
 */
public enum ChannelTradeStatus {

    /** 支付成功 */
    SUCCESS,
    /** 支付中（用户未完成/处理中） */
    PAYING,
    /** 支付失败（明确失败终态） */
    FAILED,
    /** 已关闭 */
    CLOSED,
    /** 渠道侧未找到该订单 */
    NOT_FOUND
}
