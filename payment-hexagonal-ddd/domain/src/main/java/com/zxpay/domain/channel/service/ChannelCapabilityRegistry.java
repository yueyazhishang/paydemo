package com.zxpay.domain.channel.service;

import com.zxpay.domain.channel.model.CapabilityRequirement;
import com.zxpay.domain.channel.model.ChannelCapability;
import com.zxpay.domain.channel.model.ChannelCode;
import com.zxpay.domain.channel.port.ChannelCapabilityQuery;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 通道能力注册中心（领域服务）。
 *
 * <p>职责边界很清楚：<b>只回答「能不能」，不回答「选哪个」</b>。
 * <ul>
 *   <li>「能不能」= 能力匹配，属于通道上下文，纯规则，无状态。</li>
 *   <li>「选哪个」= 路由决策，属于支付上下文 {@code ChannelRoutingService}，
 *       要综合费率、成功率、健康度、灰度、商户偏好等动态因素。</li>
 * </ul>
 * 把两者混在一个类里，是支付中台最常见的腐化起点：路由类会越来越大，
 * 最后既改不动也不敢测。
 *
 * <p>本类通过构造器注入 {@link ChannelCapabilityQuery} 端口，不依赖任何框架，
 * 单测时传入一个基于 Map 的假实现即可覆盖全部路由分支。
 */
public class ChannelCapabilityRegistry {

    private final ChannelCapabilityQuery capabilityQuery;

    public ChannelCapabilityRegistry(ChannelCapabilityQuery capabilityQuery) {
        this.capabilityQuery = capabilityQuery;
    }

    /**
     * 对所有已启用通道做能力评估，返回全部结果（含被拒绝的及其原因）。
     *
     * <p>返回全部而非仅返回可用通道，是为了让调用方能输出完整的决策日志：
     * 哪些通道被排除了、为什么。这在排查「无可用通道」类故障时价值极高。
     */
    public List<CapabilityMatcher.MatchResult> evaluate(CapabilityRequirement requirement) {
        return capabilityQuery.findAllEnabled().stream()
                .map(capability -> CapabilityMatcher.match(capability, requirement))
                .sorted(Comparator.comparingInt(this::priorityOf))
                .toList();
    }

    /** 返回能力匹配的候选通道，按基础优先级升序（数值越小越优先）。 */
    public List<ChannelCapability> eligibleChannels(CapabilityRequirement requirement) {
        return evaluate(requirement).stream()
                .filter(CapabilityMatcher.MatchResult::matched)
                .map(CapabilityMatcher.MatchResult::capability)
                .toList();
    }

    /**
     * 为故障通道寻找能力对等的备用通道。
     *
     * <p>「能力对等」是硬约束：不能为了保成功率切到一个语义不同的通道上。
     * 例如原通道支持 AUTH_ONLY（先授权后请款），备用通道若只支持 SALE（即时扣款），
     * 切换后资金流会直接错乱——用户还没发货就被扣款。宁可失败，也不能错切。
     */
    public Optional<ChannelCapability> fallbackFor(ChannelCode failed, CapabilityRequirement requirement) {
        return eligibleChannels(requirement).stream()
                .filter(capability -> capability.channel() != failed)
                .filter(capability -> capability.covers(requirement.requiredCapabilities()))
                .findFirst();
    }

    /** 加载指定通道能力。通道不存在或未启用时返回空。 */
    public Optional<ChannelCapability> load(ChannelCode channel) {
        return capabilityQuery.findByChannel(channel).filter(ChannelCapability::enabled);
    }

    private int priorityOf(CapabilityMatcher.MatchResult result) {
        ChannelCapability capability = result.capability();
        return capability == null ? Integer.MAX_VALUE : capability.basePriority();
    }
}
