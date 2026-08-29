package com.demo.payment.domain.acquiring.model.entity;

import com.demo.payment.domain.acquiring.model.aggregate.PaymentOrderId;
import com.demo.payment.shared.money.Money;

import java.time.Instant;

/**
 * 退款单 —— 隶属于 PaymentOrder 聚合（局部实体）。
 *
 * <p><b>关键设计：退款金额校验由聚合根 PaymentOrder 统一负责。</b>
 * 本实体自身不校验"是否超额"，因为那样做在并发下是无效的：
 * 两个线程同时读取"已退 0"，各自校验通过，同时写入，结果超额退款。
 * 必须由聚合根在同一把锁内完成"读-校验-写"。
 *
 * <p>这也正是退款不能独立成聚合的根本原因。
 */
public class RefundOrder {

    private final String refundNo;
    private final PaymentOrderId orderId;
    private final Money amount;
    private final String reason;
    private final Instant createdAt;

    private RefundStatus status;
    private String channelRefundId;
    private String failReason;
    private Instant finishedAt;

    private RefundOrder(String refundNo, PaymentOrderId orderId, Money amount,
                        String reason, RefundStatus status) {
        this.refundNo = refundNo;
        this.orderId = orderId;
        this.amount = amount;
        this.reason = reason;
        this.status = status;
        this.createdAt = Instant.now();
    }

    public static RefundOrder create(String refundNo, PaymentOrderId orderId,
                                     Money amount, String reason) {
        return new RefundOrder(refundNo, orderId, amount, reason, RefundStatus.PENDING);
    }

    public void markProcessing() { this.status = RefundStatus.PROCESSING; }

    public void markSucceeded(String channelRefundId, Instant finishedAt) {
        this.status = RefundStatus.SUCCEEDED;
        this.channelRefundId = channelRefundId;
        this.finishedAt = finishedAt != null ? finishedAt : Instant.now();
    }

    public void markFailed(String reason) {
        this.status = RefundStatus.FAILED;
        this.failReason = reason;
        this.finishedAt = Instant.now();
    }

    /**
     * 是否计入"已退款"限额。
     *
     * <p><b>FAILED 的退款不计入</b>：否则一次失败退款会永久占用退款额度，
     * 导致后续无法退款。但 <b>PENDING / PROCESSING 必须计入</b>，
     * 因为通道可能稍后成功 —— 这是防止并发超额退款的关键保守策略。
     */
    public boolean countsTowardLimit() {
        return status == RefundStatus.SUCCEEDED
                || status == RefundStatus.PENDING
                || status == RefundStatus.PROCESSING;
    }

    public String refundNo() { return refundNo; }
    public PaymentOrderId orderId() { return orderId; }
    public Money amount() { return amount; }
    public String reason() { return reason; }
    public Instant createdAt() { return createdAt; }
    public RefundStatus status() { return status; }
    public String channelRefundId() { return channelRefundId; }
    public String failReason() { return failReason; }
    public Instant finishedAt() { return finishedAt; }

    public enum RefundStatus {
        /** 待处理（已占用退款额度） */
        PENDING,
        /** 处理中（已提交通道） */
        PROCESSING,
        SUCCEEDED,
        FAILED
    }

    @Override
    public String toString() {
        return "RefundOrder{refundNo='" + refundNo + "', amount=" + amount
                + ", status=" + status + "}";
    }
}
