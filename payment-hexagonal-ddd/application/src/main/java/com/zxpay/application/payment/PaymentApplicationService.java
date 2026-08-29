package com.zxpay.application.payment;

import com.zxpay.application.dto.PaymentCommands;
import com.zxpay.application.port.out.DomainEventPublisher;
import com.zxpay.application.port.out.IdempotencyStore;
import com.zxpay.domain.channel.model.ChannelCode;
import com.zxpay.domain.channel.service.ChannelCapabilityRegistry;
import com.zxpay.domain.merchant.model.Merchant;
import com.zxpay.domain.merchant.model.MerchantApp;
import com.zxpay.domain.merchant.port.MerchantRepository;
import com.zxpay.domain.payment.model.ChannelInteraction;
import com.zxpay.domain.payment.model.ChannelRequest;
import com.zxpay.domain.payment.model.ChannelResult;
import com.zxpay.domain.payment.model.ChannelResultApplication;
import com.zxpay.domain.payment.model.PaymentAttempt;
import com.zxpay.domain.payment.model.PaymentInstruction;
import com.zxpay.domain.payment.model.PaymentOrder;
import com.zxpay.domain.payment.model.PaymentOrderId;
import com.zxpay.domain.payment.model.PaymentStatus;
import com.zxpay.domain.payment.port.ChannelGatewayRegistry;
import com.zxpay.domain.payment.port.ChannelPaymentPort;
import com.zxpay.domain.payment.port.ChannelQueryPort;
import com.zxpay.domain.payment.port.PaymentOrderRepository;
import com.zxpay.domain.payment.service.ChannelRoutingService;
import com.zxpay.domain.payment.service.IdempotencyKeyFactory;
import com.zxpay.sharedkernel.exception.DomainException;
import com.zxpay.sharedkernel.money.Money;
import com.zxpay.sharedkernel.time.ClockHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 支付应用层服务：用例编排。
 *
 * <p>这一层的职责边界：<b>编排，不含业务规则</b>。
 * <ul>
 *   <li>业务规则（状态能不能转、金额超没超）在领域层的聚合与领域服务里。</li>
 *   <li>技术细节（HTTP、签名、持久化）在基础设施层。</li>
 *   <li>本层只负责：加载聚合 → 调用领域行为 → 调端口 → 保存 → 发事件。</li>
 * </ul>
 *
 * <p>判断某段代码该不该放应用层的简单标准：
 * 如果把它删掉，业务规则会变吗？不会的话，它就属于应用层（编排）；
 * 会的话，它就该下沉到领域层。
 *
 * <h3>事务边界</h3>
 * <p>一个用例 = 一个事务。但<b>通道调用在事务内</b>还是事务外，需要权衡：
 * <ul>
 *   <li>放在事务内：一致性好，但事务时间长，数据库连接被长时间占用。</li>
 *   <li>放在事务外：吞吐高，但可能出现「库里没记录、通道已扣款」。</li>
 * </ul>
 * 这里采用<b>先落库、再调通道、再落库</b>的两段式：
 * 第一次落库保住「尝试已发起」的证据，即便调用后崩溃，
 * 补偿任务也能根据 attempt 记录主动查单恢复。
 */
@Service
public class PaymentApplicationService {

    /** 补偿任务扫描的阈值：中间态超过该时长仍未推进，就主动查单。 */
    private static final Duration COMPENSATE_THRESHOLD = Duration.ofMinutes(3);

    private final PaymentOrderRepository orderRepository;
    private final MerchantRepository merchantRepository;
    private final ChannelRoutingService routingService;
    private final ChannelCapabilityRegistry capabilityRegistry;
    private final ChannelGatewayRegistry gatewayRegistry;
    private final DomainEventPublisher eventPublisher;
    private final IdempotencyStore idempotencyStore;

    public PaymentApplicationService(PaymentOrderRepository orderRepository,
                                     MerchantRepository merchantRepository,
                                     ChannelRoutingService routingService,
                                     ChannelCapabilityRegistry capabilityRegistry,
                                     ChannelGatewayRegistry gatewayRegistry,
                                     DomainEventPublisher eventPublisher,
                                     IdempotencyStore idempotencyStore) {
        this.orderRepository = orderRepository;
        this.merchantRepository = merchantRepository;
        this.routingService = routingService;
        this.capabilityRegistry = capabilityRegistry;
        this.gatewayRegistry = gatewayRegistry;
        this.eventPublisher = eventPublisher;
        this.idempotencyStore = idempotencyStore;
    }

    // =====================================================================
    // 下单
    // =====================================================================

    /**
     * 创建支付并下发通道。
     *
     * <p>完整链路：商户校验 → 接口幂等 → 业务幂等 → 通道路由 → 建单 → 下发通道。
     *
     * <p>三层幂等在这里全部体现：
     * <ol>
     *   <li><b>接口层</b>：{@code Idempotency-Key}（{@link IdempotencyStore}）</li>
     *   <li><b>业务层</b>：{@code (appId, merchantOrderNo)} 唯一查找</li>
     *   <li><b>通道层</b>：{@code IdempotencyKeyFactory} 确定性生成的幂等键</li>
     * </ol>
     */
    @Transactional
    public PaymentCommands.PaymentResult createPayment(PaymentCommands.CreatePaymentCommand command) {
        Instant now = ClockHolder.now();

        // ---- 1. 商户准入 ----
        Merchant merchant = merchantRepository.findByAppId(command.appId())
                .orElseThrow(() -> new DomainException("MERCHANT_NOT_FOUND",
                        "merchant not found for app " + command.appId().value()));
        merchant.requireAcceptableForNewPayment();
        MerchantApp app = merchant.requireApp(command.appId());

        // ---- 2. 接口幂等 ----
        String idempotencyKey = command.appId().value() + ":" + command.idempotencyKey();
        if (command.idempotencyKey() != null && !command.idempotencyKey().isBlank()) {
            Optional<String> cached = idempotencyStore.findResult(idempotencyKey);
            if (cached.isPresent()) {
                return orderRepository.findById(PaymentOrderId.of(cached.get()))
                        .map(this::toResult)
                        .orElseThrow(() -> new DomainException("ORDER_NOT_FOUND", cached.get()));
            }
        }

        // ---- 3. 业务幂等 ----
        Optional<PaymentOrder> existing =
                orderRepository.findByMerchantOrderNo(command.appId(), command.merchantOrderNo());
        if (existing.isPresent()) {
            return toResult(existing.get());
        }

        // ---- 4. 建单 ----
        PaymentInstruction instruction = buildInstruction(command);
        PaymentOrder order = PaymentOrder.create(
                PaymentOrderId.generate(), merchant.id(), command.appId(),
                command.merchantOrderNo(), instruction, now);

        // ---- 5. 路由 ----
        var decision = routingService.route(app, instruction, Set.of());
        if (decision.selectedChannel().isEmpty()) {
            order.markRoutingFailed("NO_AVAILABLE_CHANNEL", String.join("; ", decision.rejections()), now);
            orderRepository.save(order);
            publishEvents(order);
            return toResult(order);
        }
        order.assignChannel(decision.requireChannel(), now);
        orderRepository.save(order);
        publishEvents(order);

        // ---- 6. 下发通道 ----
        submitToChannel(order, app);

        // ---- 7. 记录接口幂等结果 ----
        if (command.idempotencyKey() != null && !command.idempotencyKey().isBlank()) {
            idempotencyStore.saveResult(idempotencyKey, order.id().value(), Duration.ofHours(24));
        }

        return toResult(order);
    }

    // =====================================================================
    // 下发通道
    // =====================================================================

    /**
     * 向通道发起（或重试）一次支付。
     *
     * <p>关键：{@code beginAttempt} 会复用同通道可重试的尝试，
     * 从而<b>保住通道幂等键</b>。这是重试安全的唯一保障。
     */
    @Transactional
    public PaymentCommands.PaymentResult submitToChannel(PaymentOrderId orderId) {
        PaymentOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new DomainException("ORDER_NOT_FOUND", orderId.value()));
        Merchant merchant = merchantRepository.findByAppId(order.appId())
                .orElseThrow(() -> new DomainException("MERCHANT_NOT_FOUND", order.appId().value()));
        submitToChannel(order, merchant.requireApp(order.appId()));
        return toResult(order);
    }

    private void submitToChannel(PaymentOrder order, MerchantApp app) {
        Instant now = ClockHolder.now();

        if (order.currentChannel() == null) {
            throw new DomainException("CHANNEL_NOT_ASSIGNED",
                    "no channel assigned for order " + order.id().value());
        }

        ChannelPaymentPort port = gatewayRegistry.paymentPortOf(order.currentChannel())
                .orElseThrow(() -> new DomainException("CHANNEL_PORT_NOT_FOUND",
                        "no payment port for channel " + order.currentChannel()));

        PaymentAttempt attempt = order.beginAttempt(order.currentChannel(), now);
        ChannelRequest request = buildChannelRequest(order, attempt);

        ChannelResult result = port.pay(request);
        ChannelResultApplication application = order.applyChannelResult(result, now);

        orderRepository.save(order);
        publishEvents(order);

        // 可切换的失败：换一家通道再试一次（只试一次，避免级联放大）
        if (shouldSwitchChannel(result, application)) {
            tryFallback(order, app, now);
        }
    }

    /**
     * 是否应该切换通道。
     *
     * <p>条件很苛刻：必须是通道侧可切换的失败，且确实还存在其他候选。
     * 风控拦截（{@code switchable=false}）绝不切换——换哪家都会被拦，
     * 切了只是把拒绝率平摊到别的通道上，还会污染健康度指标。
     */
    private boolean shouldSwitchChannel(ChannelResult result, ChannelResultApplication application) {
        return application == ChannelResultApplication.APPLIED
                && result.failureOptional().isPresent()
                && result.failureOptional().get().switchable()
                && !result.failureOptional().get().requiresQueryBeforeDecision();
    }

    private void tryFallback(PaymentOrder order, MerchantApp app, Instant now) {
        Optional<ChannelCode> fallback = routingService.reroute(app, order);
        if (fallback.isEmpty()) {
            return;   // 没有能力对等的备用通道，宁可失败也不能错切
        }

        order.switchChannel(fallback.get(), "primary channel failed", now);
        orderRepository.save(order);
        publishEvents(order);

        gatewayRegistry.paymentPortOf(fallback.get()).ifPresent(port -> {
            PaymentAttempt attempt = order.beginAttempt(fallback.get(), now);
            ChannelResult retryResult = port.pay(buildChannelRequest(order, attempt));
            order.applyChannelResult(retryResult, now);
            orderRepository.save(order);
            publishEvents(order);
        });
    }

    // =====================================================================
    // 主动查单（补偿链路）
    // =====================================================================

    /**
     * 主动查单并同步状态。
     *
     * <p><b>这不是可选兜底，而是必需链路。</b>
     * 通道通知一定会丢（回调地址抖动、发版重启、重试次数耗尽），
     * 只依赖通知必然出现「用户已付款、订单显示待支付」。
     */
    @Transactional
    public PaymentCommands.PaymentResult queryAndSync(PaymentOrderId orderId) {
        Instant now = ClockHolder.now();
        PaymentOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new DomainException("ORDER_NOT_FOUND", orderId.value()));

        if (order.status().isTerminal()) {
            return toResult(order);
        }
        if (order.currentChannel() == null) {
            return toResult(order);
        }

        ChannelQueryPort port = gatewayRegistry.queryPortOf(order.currentChannel())
                .orElseThrow(() -> new DomainException("CHANNEL_PORT_NOT_FOUND",
                        "no query port for channel " + order.currentChannel()));

        PaymentAttempt attempt = order.currentAttempt()
                .orElseThrow(() -> new DomainException("NO_ACTIVE_ATTEMPT", orderId.value()));

        var query = attempt.hasTransactionId()
                ? ChannelQueryPort.ChannelQueryRequest.byTransactionId(
                        order.currentChannel(), order.id(), attempt.attemptId(), attempt.channelTransactionId())
                : ChannelQueryPort.ChannelQueryRequest.byMerchantOrderNo(
                        order.currentChannel(), order.id(), attempt.attemptId(), attempt.channelOrderNo());

        ChannelResult result = port.query(query);
        ChannelResultApplication application = order.applyChannelResult(result, now);

        // 终态冲突：订单已关闭但通道侧已付款 —— 必须触发补偿退款
        if (application == ChannelResultApplication.TERMINAL_CONFLICT_PAID_AFTER_CLOSE) {
            handlePaidAfterClose(order, result);
        }

        orderRepository.save(order);
        publishEvents(order);
        return toResult(order);
    }

    /**
     * 处理「订单已关闭但用户已付款」。
     *
     * <p>这是支付系统最危险的资金场景：钱进了我们的账，
     * 订单却是关闭状态——既不发货也不退款，钱凭空消失在账务里。
     *
     * <p>正确处置：自动发起原路退款。本 Demo 仅记录告警，
     * 生产必须接退款流程并触发人工跟进。
     */
    private void handlePaidAfterClose(PaymentOrder order, ChannelResult result) {
        System.err.printf("[CRITICAL] order %s was closed but channel reported PAID. "
                        + "channel=%s txn=%s amount=%s -> must trigger auto-refund%n",
                order.id().value(), result.channel(), result.channelTransactionId(), result.paidAmount());
    }

    // =====================================================================
    // 关单
    // =====================================================================

    @Transactional
    public PaymentCommands.PaymentResult closePayment(PaymentOrderId orderId, String reason) {
        Instant now = ClockHolder.now();
        PaymentOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new DomainException("ORDER_NOT_FOUND", orderId.value()));

        // 先关本单（领域层会拒绝已支付的订单）
        order.close(reason, now);
        orderRepository.save(order);
        publishEvents(order);

        // 再通知通道关单。通道关单失败不影响本单状态——
        // 本单已关闭，通道侧订单到期后自然失效，最终一致。
        if (order.currentChannel() != null) {
            gatewayRegistry.closePortOf(order.currentChannel()).ifPresent(port -> {
                order.currentAttempt().ifPresent(attempt -> port.close(
                        ChannelCloseRequest(order.id(), attempt, order.merchantOrderNo())));
            });
        }
        return toResult(order);
    }

    private com.zxpay.domain.payment.port.ChannelClosePort.ChannelCloseRequest
            ChannelCloseRequest(PaymentOrderId orderId, PaymentAttempt attempt, String merchantOrderNo) {
        return com.zxpay.domain.payment.port.ChannelClosePort.ChannelCloseRequest.of(
                attempt.channel(), orderId, attempt.attemptId(), merchantOrderNo,
                IdempotencyKeyFactory.closeKey(orderId));
    }

    // =====================================================================
    // 请款（海外 auth 模式）
    // =====================================================================

    /**
     * 发起请款。
     *
     * <p>领域层会校验：状态必须是已授权、授权未过期、请款金额不超过授权额。
     * 校验失败直接抛领域异常，不会打到通道。
     */
    @Transactional
    public PaymentCommands.PaymentResult capture(PaymentOrderId orderId, Money amount) {
        Instant now = ClockHolder.now();
        PaymentOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new DomainException("ORDER_NOT_FOUND", orderId.value()));

        int captureSeq = order.requestCapture(amount, now);
        orderRepository.save(order);
        publishEvents(order);

        gatewayRegistry.capturePortOf(order.currentChannel()).ifPresentOrElse(
                port -> {
                    PaymentAttempt attempt = order.currentAttempt().orElseThrow();
                    var request = com.zxpay.domain.payment.port.ChannelCapturePort.ChannelCaptureRequest.of(
                            order.currentChannel(), order.id(), attempt.attemptId(),
                            order.authorization().orElseThrow().channelAuthorizationId(),
                            amount,
                            IdempotencyKeyFactory.captureKey(orderId, captureSeq));
                    ChannelResult result = port.capture(request);
                    order.applyCaptureResult(result, now);
                    orderRepository.save(order);
                    publishEvents(order);
                },
                () -> {
                    throw new DomainException("CAPTURE_NOT_SUPPORTED",
                            "channel " + order.currentChannel() + " does not support capture");
                });

        return toResult(order);
    }

    // =====================================================================
    // 补偿任务
    // =====================================================================

    /**
     * 扫描并推进长时间停留在中间态的订单。
     *
     * <p>由定时任务调用。这里刻意不逐单处理异常：
     * 单笔失败不能中断整批扫描，否则一笔脏数据会卡死所有补偿。
     */
    public int compensatePendingPayments() {
        Instant threshold = ClockHolder.now().minus(COMPENSATE_THRESHOLD);
        List<PaymentStatus> pendingStatuses = List.of(
                PaymentStatus.ROUTING, PaymentStatus.PAYING,
                PaymentStatus.USERPAYING, PaymentStatus.AUTHORIZED, PaymentStatus.CAPTURING);

        List<PaymentOrder> pending = orderRepository.findPendingBefore(pendingStatuses, threshold, 200);

        int processed = 0;
        for (PaymentOrder order : pending) {
            try {
                queryAndSync(order.id());
                processed++;
            } catch (Exception e) {
                System.err.printf("[compensate-failed] order=%s error=%s%n",
                        order.id().value(), e.getMessage());
            }
        }
        return processed;
    }

    // =====================================================================
    // 内部
    // =====================================================================

    private PaymentInstruction buildInstruction(PaymentCommands.CreatePaymentCommand command) {
        return new PaymentInstruction(
                command.paymentMethod(),
                command.interactionMode(),
                command.amount(),
                command.captureMode(),
                command.expiry(),
                command.subject(),
                command.scene(),
                command.payerIdentity(),
                command.notifyUrl(),
                command.returnUrl(),
                command.metadata());
    }

    /**
     * 构造下发通道的请求。
     *
     * <p>这里是「领域意图」翻译成「技术请求」的地方：
     * 支付指令 + 路由结果 + 尝试信息 → 通道请求。
     * 注意幂等键取自 attempt（确定性生成并已持久化），
     * 而不是在这里随机生成一个。
     */
    private ChannelRequest buildChannelRequest(PaymentOrder order, PaymentAttempt attempt) {
        return new ChannelRequest(
                attempt.attemptId(),
                order.id(),
                attempt.channel(),
                attempt.idempotencyKey(),
                order.merchantOrderNo(),
                attempt.channelOrderNo(),
                order.instruction().amount(),
                order.instruction().paymentMethod(),
                order.instruction().interactionMode(),
                order.instruction().payerIdentity(),
                order.instruction().subject(),
                order.instruction().scene(),
                order.expireAt(),
                order.instruction().captureMode(),
                platformNotifyUrl(attempt.channel()),
                order.instruction().returnUrl(),
                order.instruction().metadata());
    }

    /**
     * 我方接收通道回调的地址。
     *
     * <p>注意这是<b>我们的</b>地址，不是商户的。通道通知先到我们这里，
     * 验签、推进状态后，再由我们通知商户——中间这一层不能省，
     * 否则商户要自己对接九套回调格式。
     */
    private String platformNotifyUrl(ChannelCode channel) {
        return "https://pay.example.com/callback/" + channel.name().toLowerCase(Locale.ROOT);
    }

    private void publishEvents(PaymentOrder order) {
        eventPublisher.publishAll(order.domainEvents());
        order.clearDomainEvents();
    }

    private PaymentCommands.PaymentResult toResult(PaymentOrder order) {
        ChannelInteraction interaction = order.currentAttempt()
                .flatMap(PaymentAttempt::interaction)
                .orElse(null);
        String rawStatus = order.currentAttempt()
                .flatMap(PaymentAttempt::lastRawStatus)
                .map(rs -> rs.rawStatus())
                .orElse(null);

        return PaymentCommands.PaymentResult.of(
                order.id(), order.merchantOrderNo(), order.status(), order.currentChannel(),
                interaction, rawStatus, order.lastFailureCode(), order.lastFailureMessage(),
                order.expireAt(), order.createdAt());
    }
}
