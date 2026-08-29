package com.zxpay.domain.payment.event;

import com.zxpay.domain.channel.model.ChannelCode;
import com.zxpay.domain.channel.model.PaymentMethod;
import com.zxpay.domain.merchant.model.MerchantAppId;
import com.zxpay.domain.payment.model.PaymentAttemptId;
import com.zxpay.domain.payment.model.PaymentOrderId;
import com.zxpay.sharedkernel.event.DomainEvent;
import com.zxpay.sharedkernel.money.Money;

import java.time.Instant;

/**
 * 支付上下文的领域事件集合。
 *
 * <p>集中放在一个容器类中，而不是拆成九个文件——这些事件生命周期完全同步，
 * 一起读才看得清「一笔支付会发出哪些事实」。
 *
 * <p>事件设计原则：
 * <ol>
 *   <li><b>自包含</b>。消费方（风控、清结算、商户通知、BI）拿到事件后
 *       不需要回查支付服务就能处理。因此金额、通道、商户号全部内联。</li>
 *   <li><b>只陈述事实</b>。事件是「已经发生了什么」，不是「请你去做什么」。
 *       名称一律过去式。</li>
 *   <li><b>不携带领域对象</b>。事件要跨进程传播，携带 {@code PaymentOrder}
 *       会导致序列化后语义漂移，且出网后无法再保证对象不变量。</li>
 *   <li><b>事务提交后才发布</b>。这一点由应用层保证：<b>先落库、再发消息</b>。
 *       反过来就会「库里没成功、下游已通知商户发货」。</li>
 * </ol>
 */
public final class PaymentEvents {

    private PaymentEvents() {
    }

    /** 事件公共字段基类。 */
    private abstract static class BaseEvent implements DomainEvent {
        private final String eventId = DomainEvent.newEventId();
        private final Instant occurredAt = Instant.now();
        private final PaymentOrderId orderId;

        protected BaseEvent(PaymentOrderId orderId) {
            this.orderId = orderId;
        }

        @Override
        public String eventId() {
            return eventId;
        }

        @Override
        public Instant occurredAt() {
            return occurredAt;
        }

        @Override
        public String aggregateId() {
            return orderId.value();
        }

        public PaymentOrderId orderId() {
            return orderId;
        }
    }

    /** 支付单已创建。订阅方：风控准入、限额校验、埋点。 */
    public static final class PaymentOrderCreated extends BaseEvent {
        private final MerchantAppId appId;
        private final String merchantOrderNo;
        private final Money amount;
        private final PaymentMethod paymentMethod;

        public PaymentOrderCreated(PaymentOrderId orderId, MerchantAppId appId, String merchantOrderNo,
                                   Money amount, PaymentMethod paymentMethod) {
            super(orderId);
            this.appId = appId;
            this.merchantOrderNo = merchantOrderNo;
            this.amount = amount;
            this.paymentMethod = paymentMethod;
        }

        public MerchantAppId appId() { return appId; }
        public String merchantOrderNo() { return merchantOrderNo; }
        public Money amount() { return amount; }
        public PaymentMethod paymentMethod() { return paymentMethod; }
    }

    /** 已完成通道路由。订阅方：路由成功率统计。 */
    public static final class PaymentRouted extends BaseEvent {
        private final ChannelCode channel;

        public PaymentRouted(PaymentOrderId orderId, ChannelCode channel) {
            super(orderId);
            this.channel = channel;
        }

        public ChannelCode channel() { return channel; }
    }

    /** 一次通道尝试已发起。订阅方：通道调用量统计、链路追踪。 */
    public static final class PaymentAttemptStarted extends BaseEvent {
        private final PaymentAttemptId attemptId;
        private final ChannelCode channel;
        private final int attemptNo;
        private final boolean retryOfSameChannel;

        public PaymentAttemptStarted(PaymentOrderId orderId, PaymentAttemptId attemptId, ChannelCode channel,
                                     int attemptNo, boolean retryOfSameChannel) {
            super(orderId);
            this.attemptId = attemptId;
            this.channel = channel;
            this.attemptNo = attemptNo;
            this.retryOfSameChannel = retryOfSameChannel;
        }

        public PaymentAttemptId attemptId() { return attemptId; }
        public ChannelCode channel() { return channel; }
        public int attemptNo() { return attemptNo; }

        /** 是同一通道的重试，还是切换到新通道。两者在统计口径上必须区分。 */
        public boolean retryOfSameChannel() { return retryOfSameChannel; }
    }

    /** 支付成功（资金已到账）。订阅方：清结算、商户通知、发货触发、会计入账。 */
    public static final class PaymentSucceeded extends BaseEvent {
        private final ChannelCode channel;
        private final String channelTransactionId;
        private final Money paidAmount;
        private final Instant paidAt;
        private final MerchantAppId appId;
        private final String merchantOrderNo;

        public PaymentSucceeded(PaymentOrderId orderId, MerchantAppId appId, String merchantOrderNo,
                                ChannelCode channel, String channelTransactionId,
                                Money paidAmount, Instant paidAt) {
            super(orderId);
            this.appId = appId;
            this.merchantOrderNo = merchantOrderNo;
            this.channel = channel;
            this.channelTransactionId = channelTransactionId;
            this.paidAmount = paidAmount;
            this.paidAt = paidAt;
        }

        public MerchantAppId appId() { return appId; }
        public String merchantOrderNo() { return merchantOrderNo; }
        public ChannelCode channel() { return channel; }
        public String channelTransactionId() { return channelTransactionId; }
        public Money paidAmount() { return paidAmount; }
        public Instant paidAt() { return paidAt; }
    }

    /** 支付失败。订阅方：失败原因分析、通道健康度统计、用户挽留。 */
    public static final class PaymentFailed extends BaseEvent {
        private final ChannelCode channel;
        private final String failureCode;
        private final String failureMessage;
        private final MerchantAppId appId;
        private final String merchantOrderNo;

        public PaymentFailed(PaymentOrderId orderId, MerchantAppId appId, String merchantOrderNo,
                             ChannelCode channel, String failureCode, String failureMessage) {
            super(orderId);
            this.appId = appId;
            this.merchantOrderNo = merchantOrderNo;
            this.channel = channel;
            this.failureCode = failureCode;
            this.failureMessage = failureMessage;
        }

        public MerchantAppId appId() { return appId; }
        public String merchantOrderNo() { return merchantOrderNo; }
        public ChannelCode channel() { return channel; }
        public String failureCode() { return failureCode; }
        public String failureMessage() { return failureMessage; }
    }

    /** 通道切换。订阅方：通道质量告警、成本分析。 */
    public static final class ChannelSwitched extends BaseEvent {
        private final ChannelCode from;
        private final ChannelCode to;
        private final String reason;
        private final int attemptNo;

        public ChannelSwitched(PaymentOrderId orderId, ChannelCode from, ChannelCode to,
                               String reason, int attemptNo) {
            super(orderId);
            this.from = from;
            this.to = to;
            this.reason = reason;
            this.attemptNo = attemptNo;
        }

        public ChannelCode from() { return from; }
        public ChannelCode to() { return to; }
        public String reason() { return reason; }
        public int attemptNo() { return attemptNo; }
    }

    /** 已授权待请款。订阅方：授权到期提醒（授权 7 天过期，必须提醒商户请款）。 */
    public static final class PaymentAuthorized extends BaseEvent {
        private final ChannelCode channel;
        private final String channelAuthorizationId;
        private final Money authorizedAmount;
        private final Instant expiresAt;

        public PaymentAuthorized(PaymentOrderId orderId, ChannelCode channel, String channelAuthorizationId,
                                 Money authorizedAmount, Instant expiresAt) {
            super(orderId);
            this.channel = channel;
            this.channelAuthorizationId = channelAuthorizationId;
            this.authorizedAmount = authorizedAmount;
            this.expiresAt = expiresAt;
        }

        public ChannelCode channel() { return channel; }
        public String channelAuthorizationId() { return channelAuthorizationId; }
        public Money authorizedAmount() { return authorizedAmount; }
        public Instant expiresAt() { return expiresAt; }
    }

    /** 请款已受理。订阅方：请款成功率统计。 */
    public static final class CaptureRequested extends BaseEvent {
        private final ChannelCode channel;
        private final Money captureAmount;

        public CaptureRequested(PaymentOrderId orderId, ChannelCode channel, Money captureAmount) {
            super(orderId);
            this.channel = channel;
            this.captureAmount = captureAmount;
        }

        public ChannelCode channel() { return channel; }
        public Money captureAmount() { return captureAmount; }
    }

    /** 请款成功，资金已到账。订阅方：清结算、商户通知。 */
    public static final class PaymentCaptured extends BaseEvent {
        private final ChannelCode channel;
        private final Money capturedAmount;
        private final String channelTransactionId;

        public PaymentCaptured(PaymentOrderId orderId, ChannelCode channel,
                               Money capturedAmount, String channelTransactionId) {
            super(orderId);
            this.channel = channel;
            this.capturedAmount = capturedAmount;
            this.channelTransactionId = channelTransactionId;
        }

        public ChannelCode channel() { return channel; }
        public Money capturedAmount() { return capturedAmount; }
        public String channelTransactionId() { return channelTransactionId; }
    }

    /** 支付单已关闭（超时或商户主动关闭）。订阅方：库存释放、订单系统。 */
    public static final class PaymentClosed extends BaseEvent {
        private final String reason;
        private final MerchantAppId appId;
        private final String merchantOrderNo;

        public PaymentClosed(PaymentOrderId orderId, MerchantAppId appId, String merchantOrderNo, String reason) {
            super(orderId);
            this.appId = appId;
            this.merchantOrderNo = merchantOrderNo;
            this.reason = reason;
        }

        public MerchantAppId appId() { return appId; }
        public String merchantOrderNo() { return merchantOrderNo; }
        public String reason() { return reason; }
    }
}
