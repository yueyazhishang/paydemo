package com.zxpay.domain.payment.service;

import com.zxpay.domain.channel.model.Capability;
import com.zxpay.domain.channel.model.CapabilityRequirement;
import com.zxpay.domain.channel.model.ChannelCapability;
import com.zxpay.domain.channel.model.ChannelCode;
import com.zxpay.domain.channel.model.ChannelHealth;
import com.zxpay.domain.channel.port.ChannelHealthQuery;
import com.zxpay.domain.channel.service.CapabilityMatcher;
import com.zxpay.domain.channel.service.ChannelCapabilityRegistry;
import com.zxpay.domain.merchant.model.ChannelContract;
import com.zxpay.domain.merchant.model.MerchantApp;
import com.zxpay.domain.payment.model.CaptureMode;
import com.zxpay.domain.payment.model.PaymentInstruction;
import com.zxpay.domain.payment.model.PaymentOrder;
import com.zxpay.sharedkernel.exception.DomainException;
import com.zxpay.sharedkernel.time.ClockHolder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 通道路由服务：<b>回答「选哪家通道」，而不是「能不能选」</b>。
 *
 * <p>与 {@link ChannelCapabilityRegistry} 的分工：
 * <ul>
 *   <li>Registry 只做能力匹配（能不能接），纯规则、无状态。</li>
 *   <li>本服务做决策（选哪家），综合费率、健康度、优先级、已尝试记录。</li>
 * </ul>
 * 拆开之后，路由策略可以独立演进（今天按成本优先，明天按成功率优先，后天加灰度），
 * 而能力匹配规则保持稳定。
 *
 * <h3>路由的三层过滤</h3>
 * <ol>
 *   <li><b>签约过滤</b>：商户没签约的通道直接排除。这一步必须在能力匹配之前——
 *       否则会选出一家「能力满足但没签约」的通道，下单时才报商户号不存在。</li>
 *   <li><b>能力过滤</b>：由 {@code ChannelCapabilityRegistry} 完成。</li>
 *   <li><b>打分排序</b>：费率 + 健康度 + 基础优先级。</li>
 * </ol>
 *
 * <h3>打分为什么要把健康度算进去</h3>
 * <p>只按费率选，会永远把流量打向最便宜的通道。当该通道开始抖动时，
 * 流量不会自动撤离，失败率雪崩。把实时成功率与熔断状态纳入打分，
 * 才能让流量自动绕开故障通道——这是支付系统高可用的基本功。
 */
public class ChannelRoutingService {

    /** 连续失败达到该次数即视为应被摘除。 */
    private static final int FAILURE_ISOLATION_THRESHOLD = 10;

    /** 健康度数据的有效时长。过期数据不参与打分，避免用陈旧指标做决策。 */
    private static final Duration HEALTH_MAX_AGE = Duration.ofMinutes(5);

    private final ChannelCapabilityRegistry capabilityRegistry;
    private final ChannelHealthQuery healthQuery;

    public ChannelRoutingService(ChannelCapabilityRegistry capabilityRegistry,
                                 ChannelHealthQuery healthQuery) {
        this.capabilityRegistry = capabilityRegistry;
        this.healthQuery = healthQuery;
    }

    /**
     * 为支付单选择通道。
     *
     * @param app        商户应用（提供签约关系）
     * @param instruction 支付指令
     * @param excluded   需要排除的通道（已经试过并失败的）
     */
    public RoutingDecision route(MerchantApp app, PaymentInstruction instruction, Set<ChannelCode> excluded) {
        CapabilityRequirement requirement = buildRequirement(instruction);

        // 第一层：签约过滤
        List<ChannelContract> signed = app.contractsFor(instruction.paymentMethod());
        if (signed.isEmpty()) {
            return RoutingDecision.noChannel("no signed channel for payment method "
                    + instruction.paymentMethod() + " under app " + app.appId().value());
        }

        // 第二层：能力过滤 + 第三层：打分排序
        List<ScoredCandidate> candidates = new ArrayList<>();
        List<String> rejections = new ArrayList<>();

        for (ChannelContract contract : signed) {
            ChannelCode channel = contract.channel();
            if (excluded.contains(channel)) {
                rejections.add(channel + ": excluded (already attempted)");
                continue;
            }
            Optional<ChannelCapability> capabilityOpt = capabilityRegistry.load(channel);
            if (capabilityOpt.isEmpty()) {
                rejections.add(channel + ": capability not configured or disabled");
                continue;
            }
            ChannelCapability capability = capabilityOpt.get();

            CapabilityMatcher.MatchResult match = CapabilityMatcher.match(capability, requirement);
            if (!match.matched()) {
                rejections.add(channel + ": " + match.reason());
                continue;
            }
            candidates.add(score(capability, contract));
        }

        if (candidates.isEmpty()) {
            return RoutingDecision.noChannel("no eligible channel, rejections: " + rejections);
        }

        candidates.sort(Comparator.comparingDouble(ScoredCandidate::score));
        return RoutingDecision.selected(candidates, rejections);
    }

    /**
     * 为支付单重新路由（首次通道失败后的切换）。
     *
     * <p>关键约束：<b>备用通道必须能力对等</b>。宁可失败也不能切到一个语义不同的通道：
     * 原通道支持授权分离，备用通道只支持即时扣款，切过去就是资金事故。
     */
    public Optional<ChannelCode> reroute(MerchantApp app, PaymentOrder order) {
        Set<ChannelCode> tried = EnumSet.noneOf(ChannelCode.class);
        order.attempts().forEach(a -> tried.add(a.channel()));

        return route(app, order.instruction(), tried).selectedChannel();
    }

    // ---------- 内部 ----------

    private CapabilityRequirement buildRequirement(PaymentInstruction instruction) {
        Set<Capability> required = EnumSet.noneOf(Capability.class);

        // 交互形态决定必需能力
        switch (instruction.interactionMode()) {
            case FRONTEND_SDK -> required.add(Capability.FRONTEND_SDK_INVOKE);
            case SCAN_QR -> required.add(Capability.QR_PRECREATE);
            case REDIRECT -> required.add(Capability.HOSTED_REDIRECT);
            case BARCODE -> required.add(Capability.BARCODE_DIRECT);
            case API_ONLY -> required.add(Capability.SERVER_TO_SERVER);
            case ASYNC_INSTRUCTION -> { /* 无强制能力要求 */ }
        }

        // 手动请款要求通道支持授权与请款分离
        boolean manualCapture = instruction.captureMode() == CaptureMode.MANUAL;
        if (manualCapture) {
            required.add(Capability.AUTH_ONLY);
            required.add(Capability.CAPTURE);
        }

        return new CapabilityRequirement(
                instruction.paymentMethod(),
                instruction.interactionMode(),
                instruction.amount(),
                required,
                instruction.payerIdentity() != null,
                manualCapture);
    }

    /**
     * 打分：数值越小越优先。
     *
     * <p>公式刻意保持简单可读。生产里这里通常是可配置的权重表，
     * 甚至接一个在线学习模型；但结构不变——<b>把「打分」独立成一个可替换的策略</b>。
     */
    private ScoredCandidate score(ChannelCapability capability, ChannelContract contract) {
        double score = capability.basePriority() * 100.0d;

        ChannelHealth health = healthQuery.healthOf(capability.channel());
        boolean healthUsable = health.measuredAt() != null
                && !health.isStale(ClockHolder.now(), HEALTH_MAX_AGE);

        if (healthUsable) {
            // 成功率越低，惩罚越大。完全失败（0%）的通道惩罚 +200，基本不可能被选中
            score += (1.0d - health.successRate()) * 200.0d;

            // 耗时惩罚：超过 2 秒开始计入
            if (health.avgLatencyMillis() > 2000) {
                score += Math.min((health.avgLatencyMillis() - 2000) / 100.0d, 50.0d);
            }
            // 熔断通道几乎排除
            if (health.shouldBeIsolated(FAILURE_ISOLATION_THRESHOLD)) {
                score += 500.0d;
            }
        }

        // 成本：费率以基点计，千六 = 60，除以 10 后为 6 分，量级合适
        score += contract.feeRateBps() / 10.0d;

        return new ScoredCandidate(capability.channel(), capability, contract, score);
    }

    /** 打分后的候选通道。 */
    public record ScoredCandidate(ChannelCode channel,
                                  ChannelCapability capability,
                                  ChannelContract contract,
                                  double score) {
    }

    /** 路由决策结果。带着完整决策依据，便于排查与复盘。 */
    public record RoutingDecision(List<ScoredCandidate> ranked, List<String> rejections) {

        static RoutingDecision selected(List<ScoredCandidate> ranked, List<String> rejections) {
            return new RoutingDecision(List.copyOf(ranked), List.copyOf(rejections));
        }

        static RoutingDecision noChannel(String reason) {
            return new RoutingDecision(List.of(), List.of(reason));
        }

        public Optional<ChannelCode> selectedChannel() {
            return ranked.isEmpty() ? Optional.empty() : Optional.of(ranked.get(0).channel());
        }

        /** 取主选通道，无可用通道时抛领域异常。 */
        public ChannelCode requireChannel() {
            return selectedChannel().orElseThrow(() -> new DomainException(
                    "NO_AVAILABLE_CHANNEL", "no available channel: " + rejections));
        }

        /** 除主选外的其他可用通道，作为降级备选。 */
        public List<ChannelCode> fallbacks() {
            return ranked.stream().skip(1).map(ScoredCandidate::channel).toList();
        }
    }
}
