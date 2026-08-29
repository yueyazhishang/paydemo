package com.zx.payment.refund.domain.model;

import com.zx.payment.shared.ChannelCode;
import com.zx.payment.shared.Money;

import java.time.Instant;

/**
 * 值对象：支付事实。退款上下文对"那笔支付"的全部认知。
 *
 * 这是本工程最重要的一处建模差异，值得停下来看清楚：
 *
 *   收单上下文的 Payment  —— 一个【活】的聚合根，会 CREATED→PAYING→SUCCESS 迁移，
 *                            内部有状态机、有 attempt 列表、有不变量要守护。
 *   退款上下文的 PaidFact  —— 一个【死】的事实，不可变，没有行为，只有数据。
 *                            "这笔支付成功了，收了多少钱，走的哪个通道，什么时候。"
 *
 * 两者同名（都叫"支付"）但语义完全不同。这正是限界上下文存在的意义——
 * 同一个词在不同边界内有不同的模型。
 *
 * v1 的错误是让退款上下文直接持有收单的 Payment 聚合：
 *   Refund.applyFor(Payment payment, ...)
 * 后果不只是"跨聚合引用"的技术违规，更是模型污染：
 *   - 退款上下文被迫理解收单的完整状态机；
 *   - 收单任何一次重构都会击穿退款；
 *   - 两个上下文无法独立部署、独立演进。
 *
 * 正确做法：收单通过发布语言（PaymentSucceededV1）投递事实，
 * 退款侧的防腐层把它翻译成自己的 PaidFact。谁需要，谁翻译。
 */
public final class PaidFact {

    private final String paymentId;
    private final String merchantId;
    private final String merchantOrderNo;
    private final Money paidAmount;
    private final ChannelCode channel;
    private final String channelTradeNo;
    private final Instant paidAt;

    public PaidFact(String paymentId, String merchantId, String merchantOrderNo,
                    Money paidAmount, ChannelCode channel, String channelTradeNo, Instant paidAt) {
        if (paymentId == null || paymentId.isBlank()) {
            throw new IllegalArgumentException("支付单标识不能为空");
        }
        if (paidAmount == null || !paidAmount.isPositive()) {
            throw new IllegalArgumentException("已付金额必须大于 0");
        }
        this.paymentId = paymentId;
        this.merchantId = merchantId;
        this.merchantOrderNo = merchantOrderNo;
        this.paidAmount = paidAmount;
        this.channel = channel;
        this.channelTradeNo = channelTradeNo;
        this.paidAt = paidAt;
    }

    public String paymentId() { return paymentId; }
    public String merchantId() { return merchantId; }
    public String merchantOrderNo() { return merchantOrderNo; }
    public Money paidAmount() { return paidAmount; }
    public ChannelCode channel() { return channel; }
    public String channelTradeNo() { return channelTradeNo; }
    public Instant paidAt() { return paidAt; }

    @Override
    public String toString() {
        return String.format("PaidFact[%s, %s, %s]", paymentId, paidAmount, channel.code());
    }
}
