package com.zxpay.domain.channel.service;

import com.zxpay.domain.channel.model.Capability;
import com.zxpay.domain.channel.model.CapabilityRequirement;
import com.zxpay.domain.channel.model.ChannelCapability;
import com.zxpay.domain.channel.model.PaymentMethod;

import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;

/**
 * 能力匹配器：判断一家通道能否承接某一笔具体需求。
 *
 * <p>无状态纯函数，是典型的领域服务——它不持有数据，只表达「通道能力」这条业务规则。
 *
 * <p><b>为什么返回 {@link MatchResult} 而不是 boolean？</b>
 * 支付系统里「为什么这个通道没被选中」和「哪个通道被选中」同样重要。
 * 线上出现「无可用通道」告警时，如果日志只有一句 "no channel available"，
 * 排查就只能靠猜。带上每一条排除原因，问题在几秒内就能定位。
 */
public final class CapabilityMatcher {

    private CapabilityMatcher() {
    }

    public static MatchResult match(ChannelCapability capability, CapabilityRequirement requirement) {
        if (capability == null || requirement == null) {
            return MatchResult.rejected(null, "null input");
        }

        StringJoiner reasons = new StringJoiner("; ");

        if (!capability.isAcquirable()) {
            reasons.add("channel not acquirable: " + capability.channel());
        }

        PaymentMethod method = requirement.paymentMethod();
        if (method != null && !capability.supports(method)) {
            reasons.add("payment method unsupported: " + method);
        }

        if (requirement.interactionMode() != null && !capability.supports(requirement.interactionMode())) {
            reasons.add("interaction mode unsupported: " + requirement.interactionMode());
        }

        Optional<String> amountIssue = capability.validateAmount(requirement.amount());
        amountIssue.ifPresent(reasons::add);

        Set<Capability> missing = capability.missingCapabilities(requirement.requiredCapabilities());
        if (!missing.isEmpty()) {
            reasons.add("missing capabilities: " + missing);
        }

        // 支付方式需要用户身份（如微信 JSAPI 的 openid）但我们没拿到，直接排除
        if (method != null && method.isPayerIdentityRequired() && !requirement.payerIdentityAvailable()) {
            reasons.add("payer identity required but unavailable for " + method);
        }

        // 业务要求授权与请款分离，通道必须同时支持 AUTH_ONLY 与 CAPTURE
        if (requirement.manualCaptureRequired()
                && !(capability.supports(Capability.AUTH_ONLY) && capability.supports(Capability.CAPTURE))) {
            reasons.add("manual capture required but channel does not support AUTH_ONLY+CAPTURE");
        }

        String reason = reasons.length() == 0 ? null : reasons.toString();
        return reason == null
                ? MatchResult.matched(capability)
                : MatchResult.rejected(capability, reason);
    }

    /**
     * 匹配结果。
     *
     * @param capability  被评估的通道
     * @param matched     是否匹配
     * @param reason      不匹配的原因；匹配时为 null
     * @param missing     缺失的能力位，便于上层做「降级到次优通道」决策
     */
    public record MatchResult(ChannelCapability capability, boolean matched, String reason, Set<Capability> missing) {

        static MatchResult matched(ChannelCapability capability) {
            return new MatchResult(capability, true, null, Set.of());
        }

        static MatchResult rejected(ChannelCapability capability, String reason) {
            Set<Capability> missing = capability == null ? Set.of() : Set.of();
            return new MatchResult(capability, false, reason, missing);
        }

        public Optional<String> reasonOptional() {
            return Optional.ofNullable(reason);
        }
    }
}
