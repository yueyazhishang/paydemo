package com.zxpay.application.refund;

import com.zxpay.application.dto.PaymentCommands;
import com.zxpay.application.port.out.DomainEventPublisher;
import com.zxpay.domain.channel.model.ChannelCapability;
import com.zxpay.domain.merchant.model.Merchant;
import com.zxpay.domain.merchant.port.MerchantRepository;
import com.zxpay.domain.payment.model.PaymentOrder;
import com.zxpay.domain.payment.model.PaymentOrderId;
import com.zxpay.domain.payment.port.ChannelGatewayRegistry;
import com.zxpay.domain.payment.port.PaymentOrderRepository;
import com.zxpay.domain.refund.model.ChannelRefundResult;
import com.zxpay.domain.refund.model.RefundOrder;
import com.zxpay.domain.refund.model.RefundOrderId;
import com.zxpay.domain.refund.port.ChannelRefundPort;
import com.zxpay.domain.refund.port.ChannelRefundQueryPort;
import com.zxpay.domain.refund.port.RefundOrderRepository;
import com.zxpay.domain.refund.service.RefundEligibilityService;
import com.zxpay.domain.channel.service.ChannelCapabilityRegistry;
import com.zxpay.sharedkernel.exception.DomainException;
import com.zxpay.sharedkernel.money.Money;
import com.zxpay.sharedkernel.time.ClockHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 退款应用层服务。
 *
 * <p><b>本服务最能体现「跨聚合一致性」的处理手法。</b>
 *
 * <p>支付单与退款单是两个聚合，但退款必须保证「累计退款不超过实付」。
 * 这里用<b>预留 - 确认</b>两段式在同一个事务里完成：
 * <ol>
 *   <li>{@code PaymentOrder.reserveRefund(amount)}：占用金额（refundingAmount 增加）</li>
 *   <li>创建退款单并提交通道</li>
 *   <li>成功：{@code applyRefundSucceeded} 落定；
 *       失败：{@code applyRefundFailed} 释放占用</li>
 * </ol>
 *
 * <p>因为两个聚合在同一个数据库、同一个事务中，
 * 所以<b>不需要分布式事务，也没有最终一致的窗口期</b>。
 * 这是 DDD 里处理跨聚合一致性的首选方案：
 * 先问「能不能放进一个事务」，只有确实跨库跨服务时才考虑 Saga。
 *
 * <p>注意占用与释放必须成对：并发的两笔部分退款都先占用，
 * 谁超额谁在 {@code reserveRefund} 就被拒绝，不会等到通道报错才发现。
 */
@Service
public class RefundApplicationService {

    private final PaymentOrderRepository orderRepository;
    private final RefundOrderRepository refundRepository;
    private final MerchantRepository merchantRepository;
    private final ChannelCapabilityRegistry capabilityRegistry;
    private final ChannelGatewayRegistry gatewayRegistry;
    private final DomainEventPublisher eventPublisher;

    public RefundApplicationService(PaymentOrderRepository orderRepository,
                                    RefundOrderRepository refundRepository,
                                    MerchantRepository merchantRepository,
                                    ChannelCapabilityRegistry capabilityRegistry,
                                    ChannelGatewayRegistry gatewayRegistry,
                                    DomainEventPublisher eventPublisher) {
        this.orderRepository = orderRepository;
        this.refundRepository = refundRepository;
        this.merchantRepository = merchantRepository;
        this.capabilityRegistry = capabilityRegistry;
        this.gatewayRegistry = gatewayRegistry;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 创建退款并提交通道。
     *
     * <p>顺序很重要：<b>先校验资格，再占用金额，最后提交通道</b>。
     * 反过来做会出现「通道已退款成功、我方却发现金额超额」的烂摊子。
     */
    @Transactional
    public PaymentCommands.RefundResult createRefund(PaymentCommands.CreateRefundCommand command) {
        Instant now = ClockHolder.now();

        // ---- 1. 加载支付单 ----
        PaymentOrder order = orderRepository.findById(command.paymentOrderId())
                .orElseThrow(() -> new DomainException("ORDER_NOT_FOUND", command.paymentOrderId().value()));

        Merchant merchant = merchantRepository.findByAppId(order.appId())
                .orElseThrow(() -> new DomainException("MERCHANT_NOT_FOUND", order.appId().value()));

        // ---- 2. 业务幂等：同一商户退款单号只能有一笔 ----
        Optional<RefundOrder> existing =
                refundRepository.findByMerchantRefundNo(command.appId(), command.merchantRefundNo());
        if (existing.isPresent()) {
            return toResult(existing.get());
        }

        // ---- 3. 退款资格校验（通道能力 + 订单状态 + 金额）----
        ChannelCapability capability = capabilityRegistry.load(order.currentChannel())
                .orElseThrow(() -> new DomainException("CHANNEL_CAPABILITY_NOT_FOUND",
                        "capability not configured for " + order.currentChannel()));

        // settled 由结算上下文提供；本 Demo 未实现结算，固定为 false。
        // 生产实现应查询该笔交易是否已进入结算批次。
        boolean settled = false;

        Optional<String> rejection = RefundEligibilityService.check(
                order, command.amount(), capability, settled, now);
        if (rejection.isPresent()) {
            throw new DomainException("REFUND_NOT_ELIGIBLE", rejection.get());
        }

        // ---- 4. 占用金额（跨聚合一致性的第一步）----
        order.reserveRefund(command.amount(), now);
        orderRepository.save(order);

        // ---- 5. 创建退款单 ----
        RefundOrder refund = RefundOrder.create(
                RefundOrderId.generate(),
                order.id(),
                order.appId(),
                command.merchantRefundNo(),
                command.amount(),
                order.amount(),
                order.currentChannel(),
                order.channelTransactionId().orElseThrow(() ->
                        new DomainException("CHANNEL_TRANSACTION_ID_REQUIRED",
                                "original payment has no channel transaction id")),
                command.reason(),
                now);
        refundRepository.save(refund);

        // ---- 6. 提交通道 ----
        ChannelRefundPort port = gatewayRegistry.refundPortOf(order.currentChannel())
                .orElseThrow(() -> new DomainException("REFUND_NOT_SUPPORTED",
                        "channel " + order.currentChannel() + " does not support refund"));

        ChannelRefundResult result = port.refund(ChannelRefundPort.ChannelRefundRequest.of(
                order.currentChannel(), order.id(), refund.id(),
                refund.refundIdempotencyKey(),
                order.channelTransactionId().orElseThrow(),
                command.amount(), order.amount(),
                command.reason(),
                "https://pay.example.com/callback/refund/" + order.currentChannel().name().toLowerCase()));

        refund.markSubmitted(result.channelRefundId(), now);
        refund.applyResult(result, now);

        // ---- 7. 确认或释放占用 ----
        if (refund.status() == com.zxpay.domain.refund.model.RefundStatus.SUCCEEDED) {
            order.applyRefundSucceeded(command.amount(), now);
        } else if (refund.status() == com.zxpay.domain.refund.model.RefundStatus.FAILED) {
            order.applyRefundFailed(command.amount(), now);
        }
        // PROCESSING 状态保持占用，等退款通知或主动查单推进

        orderRepository.save(order);
        refundRepository.save(refund);

        publishEvents(order, refund);
        return toResult(refund);
    }

    // =====================================================================
    // 退款查询（通知丢失时的补偿链路）
    // =====================================================================

    /**
     * 主动查询退款结果并同步。
     *
     * <p>退款查单比支付查单更重要：卡退款本身要 5~10 个工作日，
     * 若通知再丢失，系统会一直停留在「退款中」，
     * 商户不敢重新发货、用户等不到钱。
     */
    @Transactional
    public PaymentCommands.RefundResult syncRefund(RefundOrderId refundId) {
        Instant now = ClockHolder.now();
        RefundOrder refund = refundRepository.findById(refundId)
                .orElseThrow(() -> new DomainException("REFUND_NOT_FOUND", refundId.value()));

        if (refund.status().isTerminal()) {
            return toResult(refund);
        }

        ChannelRefundQueryPort port = gatewayRegistry.refundQueryPortOf(refund.channel())
                .orElseThrow(() -> new DomainException("REFUND_QUERY_NOT_SUPPORTED",
                        "channel " + refund.channel() + " does not support refund query"));

        ChannelRefundResult result = port.queryRefund(
                ChannelRefundQueryPort.ChannelRefundQueryRequest.byIdempotencyKey(
                        refund.channel(), refund.paymentOrderId(), refund.id(),
                        refund.refundIdempotencyKey(), refund.channelTransactionId()));

        refund.applyResult(result, now);

        PaymentOrder order = orderRepository.findById(refund.paymentOrderId())
                .orElseThrow(() -> new DomainException("ORDER_NOT_FOUND", refund.paymentOrderId().value()));

        if (refund.status() == com.zxpay.domain.refund.model.RefundStatus.SUCCEEDED) {
            order.applyRefundSucceeded(refund.refundedAmount(), now);
        } else if (refund.status() == com.zxpay.domain.refund.model.RefundStatus.FAILED) {
            order.applyRefundFailed(refund.amount(), now);
        }

        orderRepository.save(order);
        refundRepository.save(refund);
        publishEvents(order, refund);

        return toResult(refund);
    }

    /** 扫描并推进长时间未终态的退款单。由定时任务调用。 */
    public int compensatePendingRefunds() {
        Instant threshold = ClockHolder.now().minus(java.time.Duration.ofMinutes(5));
        List<RefundOrder> pending = refundRepository.findPendingBefore(
                List.of(com.zxpay.domain.refund.model.RefundStatus.CREATED,
                        com.zxpay.domain.refund.model.RefundStatus.SUBMITTED,
                        com.zxpay.domain.refund.model.RefundStatus.PROCESSING),
                threshold, 200);

        int processed = 0;
        for (RefundOrder refund : pending) {
            try {
                syncRefund(refund.id());
                processed++;
            } catch (Exception e) {
                System.err.printf("[refund-compensate-failed] refund=%s error=%s%n",
                        refund.id().value(), e.getMessage());
            }
        }
        return processed;
    }

    // =====================================================================
    // 内部
    // =====================================================================

    private void publishEvents(PaymentOrder order, RefundOrder refund) {
        eventPublisher.publishAll(order.domainEvents());
        eventPublisher.publishAll(refund.domainEvents());
        order.clearDomainEvents();
        refund.clearDomainEvents();
    }

    private PaymentCommands.RefundResult toResult(RefundOrder refund) {
        return PaymentCommands.RefundResult.of(
                refund.id(), refund.paymentOrderId(), refund.merchantRefundNo(),
                refund.status(), refund.channel(), refund.amount(),
                refund.failureCode().orElse(null), refund.failureMessage().orElse(null),
                refund.createdAt());
    }
}
