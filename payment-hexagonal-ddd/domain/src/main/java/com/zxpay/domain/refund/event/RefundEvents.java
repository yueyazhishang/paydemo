package com.zxpay.domain.refund.event;

import com.zxpay.domain.channel.model.ChannelCode;
import com.zxpay.domain.merchant.model.MerchantAppId;
import com.zxpay.domain.payment.model.PaymentOrderId;
import com.zxpay.domain.refund.model.RefundOrderId;
import com.zxpay.sharedkernel.event.DomainEvent;
import com.zxpay.sharedkernel.money.Money;

import java.time.Instant;

/**
 * 退款上下文的领域事件。
 *
 * <p>退款事件的消费方与支付不同：会计系统最关心（要出红字凭证），
 * 清结算次之（影响商户结算金额），商户通知同样需要（要知道钱退没退）。
 */
public final class RefundEvents {

    private RefundEvents() {
    }

    private abstract static class BaseEvent implements DomainEvent {
        private final String eventId = DomainEvent.newEventId();
        private final Instant occurredAt = Instant.now();
        private final RefundOrderId refundId;
        private final PaymentOrderId paymentOrderId;

        protected BaseEvent(RefundOrderId refundId, PaymentOrderId paymentOrderId) {
            this.refundId = refundId;
            this.paymentOrderId = paymentOrderId;
        }

        @Override public String eventId() { return eventId; }
        @Override public Instant occurredAt() { return occurredAt; }
        @Override public String aggregateId() { return refundId.value(); }

        public RefundOrderId refundId() { return refundId; }
        public PaymentOrderId paymentOrderId() { return paymentOrderId; }
    }

    /** 退款单已创建。订阅方：会计预记账、风控。 */
    public static final class RefundOrderCreated extends BaseEvent {
        private final MerchantAppId appId;
        private final Money amount;
        private final ChannelCode channel;
        private final String reason;

        public RefundOrderCreated(RefundOrderId refundId, PaymentOrderId paymentOrderId, MerchantAppId appId,
                                  Money amount, ChannelCode channel, String reason) {
            super(refundId, paymentOrderId);
            this.appId = appId;
            this.amount = amount;
            this.channel = channel;
            this.reason = reason;
        }

        public MerchantAppId appId() { return appId; }
        public Money amount() { return amount; }
        public ChannelCode channel() { return channel; }
        public String reason() { return reason; }
    }

    /** 退款成功。订阅方：会计红冲、清结算、商户通知、库存回滚。 */
    public static final class RefundSucceeded extends BaseEvent {
        private final Money refundedAmount;
        private final ChannelCode channel;
        private final String channelRefundId;
        private final Instant refundedAt;

        public RefundSucceeded(RefundOrderId refundId, PaymentOrderId paymentOrderId, Money refundedAmount,
                               ChannelCode channel, String channelRefundId, Instant refundedAt) {
            super(refundId, paymentOrderId);
            this.refundedAmount = refundedAmount;
            this.channel = channel;
            this.channelRefundId = channelRefundId;
            this.refundedAt = refundedAt;
        }

        public Money refundedAmount() { return refundedAmount; }
        public ChannelCode channel() { return channel; }
        public String channelRefundId() { return channelRefundId; }
        public Instant refundedAt() { return refundedAt; }
    }

    /** 退款失败。订阅方：运营告警、自动重试决策。 */
    public static final class RefundFailed extends BaseEvent {
        private final Money amount;
        private final ChannelCode channel;
        private final String failureCode;
        private final String failureMessage;
        private final boolean retryable;

        public RefundFailed(RefundOrderId refundId, PaymentOrderId paymentOrderId, Money amount,
                            ChannelCode channel, String failureCode, String failureMessage, boolean retryable) {
            super(refundId, paymentOrderId);
            this.amount = amount;
            this.channel = channel;
            this.failureCode = failureCode;
            this.failureMessage = failureMessage;
            this.retryable = retryable;
        }

        public Money amount() { return amount; }
        public ChannelCode channel() { return channel; }
        public String failureCode() { return failureCode; }
        public String failureMessage() { return failureMessage; }

        /** 是否值得自动重试。风控拒绝、超期等不可重试的原因需要人工介入。 */
        public boolean retryable() { return retryable; }
    }
}
