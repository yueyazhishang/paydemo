package com.example.payment.domain.payment.model;

import com.example.payment.domain.payment.event.DomainEvent;
import com.example.payment.domain.payment.event.PaymentClosedEvent;
import com.example.payment.domain.payment.event.PaymentFailedEvent;
import com.example.payment.domain.payment.event.PaymentOrderCreatedEvent;
import com.example.payment.domain.payment.event.PaymentSucceededEvent;
import com.example.payment.domain.shared.Channel;
import com.example.payment.domain.shared.Money;
import lombok.Getter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 支付单聚合根 —— 核心域最重要的聚合。
 *
 * <p>设计要点：
 * <ul>
 *   <li>状态机流转全部收敛在聚合内，外部只能通过行为方法驱动状态，不允许 setter</li>
 *   <li>领域事件在聚合内登记（registerEvent），由应用层在事务提交后统一发布</li>
 *   <li>幂等：终态后的重复通知直接被行为方法拒绝/忽略，由应用层判断返回</li>
 * </ul>
 */
@Getter
public class PaymentOrder {

    private String paymentId;

    /** 业务订单号，幂等键（与渠道联合唯一） */
    private String bizOrderNo;

    private String merchantId;

    private Money amount;

    private Channel channel;

    private PaymentStatus status;

    /** 渠道流水号 */
    private String channelTradeNo;

    /** 收银台类型 */
    private String payType;

    /** 收银台参数（JSON），前端拉起支付所需 */
    private String payParams;

    private String failReason;

    /** 支付结果应通知的上游业务方地址（创建时由业务方指定，成功/关闭后回调） */
    private String merchantNotifyUrl;

    /** 订单有效期，超时可关单 */
    private Instant expireTime;

    /** 聚合登记但未发布的领域事件 */
    private final List<DomainEvent> pendingEvents = new ArrayList<>();

    private PaymentOrder() {
    }

    // ---------- 工厂 ----------

    /** 由持久化层重建聚合（不产生事件），供仓储实现使用 */
    public static PaymentOrder rehydrate(String paymentId, String bizOrderNo, String merchantId,
                                         Money amount, Channel channel, PaymentStatus status,
                                         String channelTradeNo, String payType, String payParams,
                                         String failReason, Instant expireTime) {
        return rehydrate(paymentId, bizOrderNo, merchantId, amount, channel, status,
                channelTradeNo, payType, payParams, failReason, null, expireTime);
    }

    /** 由持久化层重建聚合（含上游通知地址） */
    public static PaymentOrder rehydrate(String paymentId, String bizOrderNo, String merchantId,
                                         Money amount, Channel channel, PaymentStatus status,
                                         String channelTradeNo, String payType, String payParams,
                                         String failReason, String merchantNotifyUrl, Instant expireTime) {
        PaymentOrder order = new PaymentOrder();
        order.paymentId = paymentId;
        order.bizOrderNo = bizOrderNo;
        order.merchantId = merchantId;
        order.amount = amount;
        order.channel = channel;
        order.status = status;
        order.channelTradeNo = channelTradeNo;
        order.payType = payType;
        order.payParams = payParams;
        order.failReason = failReason;
        order.merchantNotifyUrl = merchantNotifyUrl;
        order.expireTime = expireTime;
        return order;
    }

    /**
     * 创建支付单（INIT 态）。应用层保证 bizOrderNo 幂等。
     */
    public static PaymentOrder create(String bizOrderNo, String merchantId,
                                      Money amount, Channel channel, int expireMinutes,
                                      String merchantNotifyUrl) {
        if (!amount.isPositive()) {
            throw new IllegalStateException("支付金额必须大于零");
        }
        PaymentOrder order = new PaymentOrder();
        order.paymentId = "PAY" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
        order.bizOrderNo = bizOrderNo;
        order.merchantId = merchantId;
        order.amount = amount;
        order.channel = channel;
        order.status = PaymentStatus.INIT;
        order.merchantNotifyUrl = merchantNotifyUrl;
        order.expireTime = Instant.now().plusSeconds(expireMinutes * 60L);
        order.registerEvent(new PaymentOrderCreatedEvent(order.paymentId, bizOrderNo,
                channel.name(), amount.getAmountMinor(), amount.getCurrency().name()));
        return order;
    }

    // ---------- 行为（状态机） ----------

    /** 渠道预下单成功，进入 PAYING，保存收银台要素 */
    public void submitToChannel(String payType, String payParams, String channelTradeNo) {
        requireStatus(PaymentStatus.INIT, "submitToChannel");
        this.status = PaymentStatus.PAYING;
        this.payType = payType;
        this.payParams = payParams;
        this.channelTradeNo = channelTradeNo;
    }

    /** 渠道预下单失败，直接 FAILED（终态，允许换渠道重新下单） */
    public void failOnSubmit(String reason) {
        requireStatus(PaymentStatus.INIT, "failOnSubmit");
        this.status = PaymentStatus.FAILED;
        this.failReason = reason;
        registerEvent(new PaymentFailedEvent(paymentId, bizOrderNo, reason));
    }

    /** 支付成功（查单确认，金额未知时不校验） */
    public void succeed(String channelTradeNo) {
        succeed(channelTradeNo, null);
    }

    /**
     * 支付成功（回调/查单确认）。
     * 不变量：确认金额必须与应付金额一致（防串单/篡改），由聚合自己守护。
     *
     * @param paidAmountMinor 渠道确认的实付金额（最小货币单位），未知时传 null
     */
    public void succeed(String channelTradeNo, Long paidAmountMinor) {
        if (paidAmountMinor != null && paidAmountMinor != this.amount.getAmountMinor()) {
            throw new AmountMismatchException(String.format(
                    "确认金额与应付不一致: 订单[%s] 应付=%d 确认=%d",
                    paymentId, this.amount.getAmountMinor(), paidAmountMinor));
        }
        if (status == PaymentStatus.SUCCESS) {
            return; // 状态机幂等：重复成功通知直接忽略
        }
        requireStatus(PaymentStatus.PAYING, "succeed");
        this.status = PaymentStatus.SUCCESS;
        if (channelTradeNo != null && !channelTradeNo.isBlank()) {
            this.channelTradeNo = channelTradeNo;
        }
        registerEvent(new PaymentSucceededEvent(paymentId, bizOrderNo, merchantId,
                amount.getAmountMinor(), amount.getCurrency().name(), this.channelTradeNo));
    }

    /** 收到渠道明确失败通知 */
    public void fail(String reason) {
        if (status == PaymentStatus.FAILED) {
            return;
        }
        requireStatus(PaymentStatus.PAYING, "fail");
        this.status = PaymentStatus.FAILED;
        this.failReason = reason;
        registerEvent(new PaymentFailedEvent(paymentId, bizOrderNo, reason));
    }

    /** 超时关单（关单前应先调用渠道关单接口，由应用层负责） */
    public void close() {
        if (status == PaymentStatus.CLOSED) {
            return;
        }
        if (status == PaymentStatus.SUCCESS) {
            throw new IllegalStateException("支付成功的订单不能关单: " + paymentId);
        }
        requireStatus(PaymentStatus.PAYING, "close");
        this.status = PaymentStatus.CLOSED;
        registerEvent(new PaymentClosedEvent(paymentId, bizOrderNo));
    }

    // ---------- 事件 ----------

    private void registerEvent(DomainEvent event) {
        this.pendingEvents.add(event);
    }

    /** 取出并清空待发布事件（应用层发布后调用） */
    public List<DomainEvent> pullEvents() {
        List<DomainEvent> events = new ArrayList<>(pendingEvents);
        pendingEvents.clear();
        return Collections.unmodifiableList(events);
    }

    /** 是否已过支付有效期（关单时机判定属于聚合知识） */
    public boolean isExpired(Instant now) {
        return expireTime != null && expireTime.isBefore(now);
    }

    private void requireStatus(PaymentStatus expected, String action) {
        if (this.status != expected) {
            throw new IllegalStateException(
                    String.format("支付单[%s]当前状态为 %s，不允许执行 %s", paymentId, status, action));
        }
    }
}
