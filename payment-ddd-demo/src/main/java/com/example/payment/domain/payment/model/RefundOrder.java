package com.example.payment.domain.payment.model;

import com.example.payment.domain.payment.event.DomainEvent;
import com.example.payment.domain.payment.event.RefundCreatedEvent;
import com.example.payment.domain.payment.event.RefundSucceededEvent;
import com.example.payment.domain.shared.Money;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 退款单聚合根。
 * 金额合法性（累计退款 ≤ 支付额）由 {@link com.example.payment.domain.service.RefundDomainService} 跨聚合校验。
 */
@Getter
public class RefundOrder {

    private String refundId;

    /** 原支付单号 */
    private String paymentId;

    private Money refundAmount;

    private RefundStatus status;

    private String channelRefundNo;

    private String reason;

    private final List<DomainEvent> pendingEvents = new ArrayList<>();

    private RefundOrder() {
    }

    /** 由持久化层重建聚合（不产生事件），供仓储实现使用 */
    public static RefundOrder rehydrate(String refundId, String paymentId, Money refundAmount,
                                        RefundStatus status, String channelRefundNo, String reason) {
        RefundOrder refund = new RefundOrder();
        refund.refundId = refundId;
        refund.paymentId = paymentId;
        refund.refundAmount = refundAmount;
        refund.status = status;
        refund.channelRefundNo = channelRefundNo;
        refund.reason = reason;
        return refund;
    }

    public static RefundOrder create(String paymentId, Money refundAmount, String reason) {
        if (!refundAmount.isPositive()) {
            throw new IllegalStateException("退款金额必须大于零");
        }
        RefundOrder refund = new RefundOrder();
        refund.refundId = "REF" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
        refund.paymentId = paymentId;
        refund.refundAmount = refundAmount;
        refund.status = RefundStatus.INIT;
        refund.reason = reason;
        refund.registerEvent(new RefundCreatedEvent(refund.refundId, paymentId,
                refundAmount.getAmountMinor(), refundAmount.getCurrency().name()));
        return refund;
    }

    /** 已提交渠道（同步返回受理/成功，或异步渠道等待回调） */
    public void submitToChannel(String channelRefundNo) {
        requireStatus(RefundStatus.INIT, "submitToChannel");
        this.status = RefundStatus.SUBMITTED;
        if (channelRefundNo != null && !channelRefundNo.isBlank()) {
            this.channelRefundNo = channelRefundNo;
        }
    }

    /** 退款成功（同步成功或回调确认） */
    public void succeed(String channelRefundNo) {
        if (status == RefundStatus.SUCCESS) {
            return; // 幂等
        }
        if (status != RefundStatus.SUBMITTED) {
            throw new IllegalStateException(
                    String.format("退款单[%s]状态为 %s，不允许标记成功", refundId, status));
        }
        this.status = RefundStatus.SUCCESS;
        if (channelRefundNo != null && !channelRefundNo.isBlank()) {
            this.channelRefundNo = channelRefundNo;
        }
        registerEvent(new RefundSucceededEvent(refundId, paymentId,
                refundAmount.getAmountMinor(), refundAmount.getCurrency().name(), this.channelRefundNo));
    }

    public void fail(String channelRefundNo) {
        if (status == RefundStatus.FAILED) {
            return;
        }
        if (status != RefundStatus.SUBMITTED) {
            throw new IllegalStateException(
                    String.format("退款单[%s]状态为 %s，不允许标记失败", refundId, status));
        }
        this.status = RefundStatus.FAILED;
        if (channelRefundNo != null && !channelRefundNo.isBlank()) {
            this.channelRefundNo = channelRefundNo;
        }
    }

    public boolean isSubmittable() {
        return status == RefundStatus.INIT || status == RefundStatus.SUBMITTED;
    }

    private void requireStatus(RefundStatus expected, String action) {
        if (this.status != expected) {
            throw new IllegalStateException(
                    String.format("退款单[%s]当前状态为 %s，不允许执行 %s", refundId, status, action));
        }
    }

    private void registerEvent(DomainEvent event) {
        this.pendingEvents.add(event);
    }

    public List<DomainEvent> pullEvents() {
        List<DomainEvent> events = new ArrayList<>(pendingEvents);
        pendingEvents.clear();
        return Collections.unmodifiableList(events);
    }
}
