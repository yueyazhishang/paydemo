package com.zx.payment.channel.domain.model;

/**
 * 值对象：通道能力矩阵。
 *
 * 这是通道网关上下文真正的领域知识——不是"通道叫什么"，而是"通道能干什么、不能干什么"。
 *
 * 为什么必须建模：不同通道的能力差异巨大，如果这些差异散落在调用方的 if 分支里，
 * 每接一个新通道就要改所有调用点。建模成能力矩阵后，调用方只需要问一句
 * "你支持部分退款吗"，通道自己回答。
 *
 * 典型差异举例：
 *   - 部分退款：微信 v3 / Stripe 支持；部分银行直连只支持全额退
 *   - 主动查单：微信/支付宝/PayPal 支持；WorldPay 经典 XML 只推不查
 *   - 对账单：微信/支付宝有标准账单文件；部分海外通道只有 API 分页查询
 *   - 关单：微信有 close 接口；Stripe 只能 void 未捕获的授权
 */
public record ChannelCapability(
        boolean supportsPartialRefund,
        boolean supportsQueryPayment,
        boolean supportsClose,
        boolean supportsStatementDownload,
        /** 通道侧最长可追溯的退款期限（天）。超过需走人工线下退款。 */
        int refundWindowDays
) {
    public static ChannelCapability full() {
        return new ChannelCapability(true, true, true, true, 365);
    }

    public static ChannelCapability minimal() {
        return new ChannelCapability(false, false, false, false, 180);
    }
}
