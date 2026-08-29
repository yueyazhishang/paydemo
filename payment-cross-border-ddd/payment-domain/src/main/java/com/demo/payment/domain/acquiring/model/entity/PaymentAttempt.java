package com.demo.payment.domain.acquiring.model.entity;

import com.demo.payment.domain.acquiring.model.aggregate.PaymentOrderId;
import com.demo.payment.domain.acquiring.model.vo.OutTradeNo;
import com.demo.payment.domain.channel.model.ChannelCode;
import com.demo.payment.shared.money.Money;

import java.time.Instant;
import java.util.Objects;

/**
 * 支付尝试实体 —— 隶属于 PaymentOrder 聚合。
 *
 * <p>存在意义：一次支付可能经过多个通道（微信失败 → 切支付宝 → 再切银联）。
 * 每次尝试都要留下完整记录，否则：
 * <ul>
 *   <li>排查问题时只能看到"最终失败"，看不到中间在哪个通道、以什么错误码失败；</li>
 *   <li>通道成功率统计无从做起（这是智能路由的数据基础）；</li>
 *   <li>回调到达时无法判断它对应哪次尝试，可能用旧尝试的结果覆盖新尝试。</li>
 * </ul>
 *
 * <p><b>为什么是实体而非值对象：</b>它有生命周期（发起 → 有结果），需要被单独标识和更新，
 * 但它的标识只在聚合内有效（用 sequence 序号），所以是<b>局部实体</b>。
 */
public class PaymentAttempt {

    /** 尝试序号，从 1 开始，用于生成唯一的 outTradeNo */
    private final int sequence;
    private final PaymentOrderId orderId;
    private final ChannelCode channelCode;
    private final OutTradeNo outTradeNo;
    private final Money amount;
    private final Instant startedAt;

    private AttemptStatus status;
    private String channelTransactionId;
    private String channelRawStatus;
    private String failReason;
    private Instant finishedAt;

    private PaymentAttempt(int sequence, PaymentOrderId orderId, ChannelCode channelCode,
                           OutTradeNo outTradeNo, Money amount) {
        this.sequence = sequence;
        this.orderId = orderId;
        this.channelCode = channelCode;
        this.outTradeNo = outTradeNo;
        this.amount = amount;
        this.startedAt = Instant.now();
        this.status = AttemptStatus.INIT;
    }

    public static PaymentAttempt start(int sequence, PaymentOrderId orderId, ChannelCode channelCode,
                                       OutTradeNo outTradeNo, Money amount) {
        return new PaymentAttempt(sequence, orderId, channelCode, outTradeNo, amount);
    }

    public void markResult(boolean success, String channelTransactionId,
                           String channelRawStatus, Instant occurredAt) {
        this.channelTransactionId = channelTransactionId;
        this.channelRawStatus = channelRawStatus;
        this.status = success ? AttemptStatus.SUCCEEDED : AttemptStatus.FAILED;
        this.finishedAt = occurredAt != null ? occurredAt : Instant.now();
    }

    /** 标记为已授权（两段式通道的授权成功，尚未请款） */
    public void markAuthorized(String channelTransactionId, Instant occurredAt) {
        this.channelTransactionId = channelTransactionId;
        this.channelRawStatus = "AUTHORIZED";
        this.status = AttemptStatus.AUTHORIZED;
        this.finishedAt = occurredAt;
    }

    public void markFailed(String reason) {
        this.status = AttemptStatus.FAILED;
        this.failReason = reason;
        this.finishedAt = Instant.now();
    }

    public boolean isAuthorized() { return status == AttemptStatus.AUTHORIZED; }
    public boolean isSucceeded() { return status == AttemptStatus.SUCCEEDED; }
    public boolean isFinished() {
        return status == AttemptStatus.SUCCEEDED || status == AttemptStatus.FAILED;
    }

    public int sequence() { return sequence; }
    public PaymentOrderId orderId() { return orderId; }
    public ChannelCode channelCode() { return channelCode; }
    public OutTradeNo outTradeNo() { return outTradeNo; }
    public Money amount() { return amount; }
    public Instant startedAt() { return startedAt; }
    public AttemptStatus status() { return status; }
    public String channelTransactionId() { return channelTransactionId; }
    public String channelRawStatus() { return channelRawStatus; }
    public String failReason() { return failReason; }
    public Instant finishedAt() { return finishedAt; }

    /** 尝试状态 */
    public enum AttemptStatus {
        /** 已创建，尚未收到通道结果 */
        INIT,
        /** 已授权（冻结额度，未请款） */
        AUTHORIZED,
        /** 支付成功 */
        SUCCEEDED,
        /** 支付失败 */
        FAILED,
        /** 已关闭 */
        CLOSED
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) { return true; }
        if (!(o instanceof PaymentAttempt other)) { return false; }
        return sequence == other.sequence && outTradeNo.equals(other.outTradeNo);
    }

    @Override
    public int hashCode() { return Objects.hash(sequence, outTradeNo); }

    @Override
    public String toString() {
        return "PaymentAttempt{seq=" + sequence + ", channel=" + channelCode
                + ", outTradeNo=" + outTradeNo + ", status=" + status + "}";
    }
}
