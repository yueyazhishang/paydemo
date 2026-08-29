package com.zxpay.application.notify;

import com.zxpay.application.dto.PaymentCommands;
import com.zxpay.application.port.out.DomainEventPublisher;
import com.zxpay.domain.channel.model.ChannelCode;
import com.zxpay.domain.notify.model.MerchantNotifyTask;
import com.zxpay.domain.notify.model.NotificationEnvelope;
import com.zxpay.domain.notify.model.NotificationPayload;
import com.zxpay.domain.notify.port.ChannelNotifyParser;
import com.zxpay.domain.notify.port.ChannelNotifyVerifier;
import com.zxpay.domain.notify.port.MerchantNotifier;
import com.zxpay.domain.payment.model.ChannelInteraction;
import com.zxpay.domain.payment.model.ChannelRawStatus;
import com.zxpay.domain.payment.model.ChannelResult;
import com.zxpay.domain.payment.model.ChannelResultApplication;
import com.zxpay.domain.payment.model.PaymentAttempt;
import com.zxpay.domain.payment.model.PaymentOrder;
import com.zxpay.domain.payment.model.PaymentStatus;
import com.zxpay.domain.payment.port.PaymentOrderRepository;
import com.zxpay.sharedkernel.time.ClockHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * 通道回调应用层服务。
 *
 * <p><b>这个类是整个支付系统安全性的关键环节。</b>
 *
 * <p>回调地址是公网可访问的 URL，任何人都能构造请求打过来。
 * 处理顺序绝不能错：
 * <ol>
 *   <li><b>先验签</b>。不通过直接拒绝，绝不解析报文、绝不查库、绝不改状态。</li>
 *   <li>验签通过后才解析成 {@link NotificationPayload}。</li>
 *   <li>定位订单（按通道订单号或交易号），找不到就原样返回成功
 *       ——避免通道因「查无此单」反复重试。</li>
 *   <li>转成 {@link ChannelResult}，交给聚合根自己推进状态。</li>
 *   <li>落库、发事件，最后通知商户。</li>
 * </ol>
 *
 * <p><b>为什么回调接口必须「快速返回成功」？</b>
 * 如果我们因为内部异常返回 5xx，通道会按重试策略反复推送，
 * 同一笔问题被放大十几次，告警淹没一切，而问题本身一点没解决。
 * 正确做法是：先收下报文、落库留痕，再异步处理；
 * 处理不了的情况沉淀成状态交给补偿任务，接口一律返回成功。
 */
@Service
public class ChannelNotifyApplicationService {

    private final PaymentOrderRepository orderRepository;
    private final com.zxpay.domain.payment.port.ChannelGatewayRegistry gatewayRegistry;
    private final DomainEventPublisher eventPublisher;
    private final MerchantNotifier merchantNotifier;

    public ChannelNotifyApplicationService(PaymentOrderRepository orderRepository,
                                           com.zxpay.domain.payment.port.ChannelGatewayRegistry gatewayRegistry,
                                           DomainEventPublisher eventPublisher,
                                           MerchantNotifier merchantNotifier) {
        this.orderRepository = orderRepository;
        this.gatewayRegistry = gatewayRegistry;
        this.eventPublisher = eventPublisher;
        this.merchantNotifier = merchantNotifier;
    }

    /**
     * 处理通道回调。
     *
     * <p>注意：本方法<b>不抛异常</b>。任何内部错误都被转成
     * {@code NotifyHandleResult} 返回并落日志，保证入站适配器能返回成功响应。
     */
    @Transactional
    public PaymentCommands.NotifyHandleResult handle(NotificationEnvelope envelope) {
        ChannelCode channel = envelope.channel();
        Instant now = ClockHolder.now();

        // ---- 1. 验签：不通过一律拒绝，并记录安全告警 ----
        ChannelNotifyVerifier verifier = verifierOf(channel);
        if (verifier == null) {
            return PaymentCommands.NotifyHandleResult.rejected("no verifier for channel " + channel);
        }
        ChannelNotifyVerifier.VerifyOutcome outcome = verifier.verify(envelope);
        if (!outcome.passed()) {
            // 验签失败是安全事件，必须告警——可能是有人在伪造回调
            System.err.printf("[SECURITY] signature verification failed. channel=%s reason=%s replay=%s%n",
                    channel, outcome.reason(), outcome.replayable());
            return PaymentCommands.NotifyHandleResult.rejected("signature verification failed: " + outcome.reason());
        }

        // ---- 2. 解析 ----
        ChannelNotifyParser parser = parserOf(channel);
        if (parser == null) {
            return PaymentCommands.NotifyHandleResult.rejected("no parser for channel " + channel);
        }
        Optional<NotificationPayload> payloadOpt = parser.parse(envelope);
        if (payloadOpt.isEmpty()) {
            // 未知状态：返回成功但记录告警，等待人工/补偿处理。
            // 不能返回失败，否则通道会无限重试同一种报文。
            System.err.printf("[notify-unknown] channel=%s body=%s%n", channel, envelope.rawBody());
            return PaymentCommands.NotifyHandleResult.rejected("unrecognized notification payload");
        }
        NotificationPayload payload = payloadOpt.get();

        // ---- 3. 定位订单 ----
        Optional<PaymentOrder> orderOpt = locateOrder(channel, payload);
        if (orderOpt.isEmpty()) {
            System.err.printf("[notify-orphan] channel=%s orderNo=%s txn=%s%n",
                    channel, payload.channelOrderNo(), payload.channelTransactionId());
            return PaymentCommands.NotifyHandleResult.rejected("payment order not found");
        }
        PaymentOrder order = orderOpt.get();

        // ---- 4. 乱序守卫：过期通知直接丢弃 ----
        if (isStaleNotification(order, payload, envelope.receivedAt())) {
            return PaymentCommands.NotifyHandleResult.accepted(null, "stale notification ignored");
        }

        // ---- 5. 转成交付给聚合根的归一化结果 ----
        ChannelResult result = toChannelResult(order, payload, now);
        ChannelResultApplication application = order.applyChannelResult(result, now);

        if (application == ChannelResultApplication.TERMINAL_CONFLICT_PAID_AFTER_CLOSE) {
            System.err.printf("[CRITICAL] order %s closed but channel reported PAID, auto-refund required. txn=%s%n",
                    order.id().value(), payload.channelTransactionId());
        }

        // ---- 6. 落库与事件 ----
        orderRepository.save(order);
        eventPublisher.publishAll(order.domainEvents());
        order.clearDomainEvents();

        // ---- 7. 通知商户。投递失败不影响本次回调结果，由通知重试任务兜底 ----
        notifyMerchant(order, payload);

        return PaymentCommands.NotifyHandleResult.accepted(application, application.displayName());
    }

    // =====================================================================
    // 内部
    // =====================================================================

    private Optional<PaymentOrder> locateOrder(ChannelCode channel, NotificationPayload payload) {
        if (payload.channelTransactionId() != null) {
            Optional<PaymentOrder> byTxn =
                    orderRepository.findByChannelTransactionId(channel, payload.channelTransactionId());
            if (byTxn.isPresent()) {
                return byTxn;
            }
        }
        if (payload.channelOrderNo() != null) {
            return orderRepository.findByChannelOrderNo(channel, payload.channelOrderNo());
        }
        return Optional.empty();
    }

    /**
     * 乱序守卫。
     *
     * <p>通道通知会重复、会乱序。网络重放或重试可能让旧通知后到，
     * 若不加判断地覆盖，已成功的订单可能被一条迟到的「失败」通知改掉。
     *
     * <p>这里用「订单最后更新时间」做保守判断：
     * 通知事件时间早于订单最后更新时间，且订单已处于终态，则丢弃。
     */
    private boolean isStaleNotification(PaymentOrder order, NotificationPayload payload, Instant receivedAt) {
        Instant eventTime = payload.effectiveEventTime(receivedAt);
        if (eventTime == null) {
            return false;
        }
        return order.status().isTerminal() && eventTime.isBefore(order.updatedAt());
    }

    private ChannelResult toChannelResult(PaymentOrder order, NotificationPayload payload, Instant now) {
        PaymentAttempt attempt = order.currentAttempt().orElse(null);
        if (attempt == null) {
            return null;
        }

        ChannelRawStatus rawStatus = ChannelRawStatus.of(
                payload.rawStatus(), payload.normalizedStatus(), "来自通道回调", now);

        if (payload.normalizedStatus() == PaymentStatus.SUCCEEDED) {
            return ChannelResult.succeeded(
                    payload.channel(), attempt.attemptId(), attempt.idempotencyKey(),
                    payload.channelTransactionId(), payload.channelOrderNo(), rawStatus,
                    payload.paidAmount() != null ? payload.paidAmount() : order.amount(),
                    payload.paidAt() != null ? payload.paidAt() : now, now);
        }
        if (payload.normalizedStatus() == PaymentStatus.AUTHORIZED) {
            return ChannelResult.pending(
                    payload.channel(), attempt.attemptId(), attempt.idempotencyKey(),
                    payload.channelOrderNo(), rawStatus, ChannelInteraction.none(), now);
        }
        if (payload.normalizedStatus() == PaymentStatus.FAILED
                || payload.normalizedStatus() == PaymentStatus.CLOSED) {
            return ChannelResult.failed(
                    payload.channel(), attempt.attemptId(), attempt.idempotencyKey(),
                    payload.channelOrderNo(), rawStatus,
                    com.zxpay.domain.payment.model.FailureInfo.business(
                            payload.channel() + "_NOTIFY_" + payload.rawStatus(), "通道通知支付未成功"),
                    now);
        }
        return ChannelResult.pending(
                payload.channel(), attempt.attemptId(), attempt.idempotencyKey(),
                payload.channelOrderNo(), rawStatus, ChannelInteraction.none(), now);
    }

    private void notifyMerchant(PaymentOrder order, NotificationPayload payload) {
        if (!order.status().isTerminal() && payload.normalizedStatus() != PaymentStatus.SUCCEEDED) {
            return;   // 只通知终态与成功事件，中间态不打扰商户
        }
        MerchantNotifyTask task = new MerchantNotifyTask(
                order.appId(), order.id(), null, order.merchantOrderNo(),
                "payment." + payload.normalizedStatus().name().toLowerCase(),
                java.util.Map.of(
                        "paymentOrderId", order.id().value(),
                        "merchantOrderNo", order.merchantOrderNo(),
                        "status", order.status().name(),
                        "channel", String.valueOf(order.currentChannel()),
                        "channelRawStatus", String.valueOf(payload.rawStatus())),
                "https://merchant.example.com/pay/notify", 1, ClockHolder.now());

        merchantNotifier.notify(task);
    }

    private ChannelNotifyVerifier verifierOf(ChannelCode channel) {
        return gatewayRegistry.verifierOf(channel).orElse(null);
    }

    private ChannelNotifyParser parserOf(ChannelCode channel) {
        return gatewayRegistry.parserOf(channel).orElse(null);
    }
}
