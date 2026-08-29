package com.zxpay.domain.channel.model;

import com.zxpay.sharedkernel.money.Money;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 通道能力矩阵：一家通道「能做什么、不能做什么」的完整声明。
 *
 * <p><b>这是整个支付中台最重要的抽象。</b>
 *
 * <p>把通道差异建模成数据，而不是代码分支，带来的直接收益：
 * <ul>
 *   <li><b>接新通道不改业务代码</b>。新增一家通道 = 新增一份能力声明 + 一个适配器实现类，
 *       业务层的下单、退款、补偿逻辑一行不动。</li>
 *   <li><b>路由前就能拒绝</b>。用户选了「部分退款」但通道不支持，在路由阶段就明确排除，
 *       而不是等退款请求打到通道才报错。</li>
 *   <li><b>能力可测试</b>。「某通道声称支持 3DS，那 3DS 挑战来了它必须能处理」
 *       这类断言可以直接写成单测。</li>
 *   <li><b>降级有依据</b>。主通道挂了要切备用通道时，切换的前提是
 *       「备用通道具备同等能力」——这就是 {@link #covers(Set)} 的用途。</li>
 * </ul>
 *
 * <p>能力声明是<b>静态配置</b>（配置中心 / DB），由基础设施层加载；
 * 动态指标（实时成功率、响应耗时）不在这里，而在路由上下文里另行输入。
 */
public record ChannelCapability(
        ChannelCode channel,

        /** 是否启用。停用的通道不参与路由，已在途的支付单仍可完成。 */
        boolean enabled,

        /** 支持的支付方式。 */
        Set<PaymentMethod> supportedMethods,

        /** 支持的前端交互形态。 */
        Set<InteractionMode> supportedInteractionModes,

        /** 能力位集合。 */
        Set<Capability> capabilities,

        /** 金额与币种约束。 */
        AmountConstraint amountConstraint,

        /** 退款约束。 */
        RefundPolicy refundPolicy,

        /** 异步通知规范。 */
        NotifySpec notifySpec,

        /** 通道侧幂等规范。 */
        IdempotencySpec idempotencySpec,

        /** 鉴权模型。 */
        AuthModel authModel,

        /** 资金到账时效。影响商户体验与结算对账周期。 */
        SettlementLatency settlementLatency,

        /** 路由基础优先级，数值越小越优先。动态指标会在此基础上调整。 */
        int basePriority
) {

    /** 资金结算时效。 */
    public record SettlementLatency(Duration typical, boolean sameDay, String note) {
    }

    public ChannelCapability {
        Objects.requireNonNull(channel, "channel");
        supportedMethods = immutableCopy(supportedMethods);
        supportedInteractionModes = immutableCopy(supportedInteractionModes);
        capabilities = immutableCopy(capabilities);
    }

    private static <T> Set<T> immutableCopy(Set<T> source) {
        return source == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(source));
    }

    // ---------- 能力查询 ----------

    public boolean supports(Capability capability) {
        return capabilities.contains(capability);
    }

    public boolean supportsAll(Set<Capability> required) {
        return capabilities.containsAll(required);
    }

    public boolean supports(PaymentMethod method) {
        return supportedMethods.contains(method);
    }

    public boolean supports(InteractionMode mode) {
        return supportedInteractionModes.contains(mode);
    }

    /**
     * 返回缺失的能力位。为空表示完全满足。
     *
     * <p>返回缺失项而不是布尔值，是为了让路由排除的原因可观测——
     * 线上出现「所有通道都被排除」时，日志里必须有据可查。
     */
    public Set<Capability> missingCapabilities(Set<Capability> required) {
        if (required == null || required.isEmpty()) {
            return Set.of();
        }
        return required.stream()
                .filter(c -> !capabilities.contains(c))
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * 是否覆盖了给定能力集——用于通道降级切换。
     *
     * <p>切换通道时最危险的事是「切到一个缺少关键能力的通道」：
     * 比如从支持 manual capture 的 Stripe 切到只支持 SALE 的通道，
     * 会导致授权与请款分离的业务语义丢失，资金直接错乱。
     */
    public boolean covers(Set<Capability> required) {
        return missingCapabilities(required).isEmpty();
    }

    public Optional<String> validateAmount(Money amount) {
        return amountConstraint.validate(amount);
    }

    /** 该通道是否可作为收单通道直接下单（钱包类不可）。 */
    public boolean isAcquirable() {
        return enabled && channel.isAcquirable();
    }
}
