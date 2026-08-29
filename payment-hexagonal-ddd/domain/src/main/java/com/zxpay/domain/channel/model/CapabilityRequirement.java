package com.zxpay.domain.channel.model;

import com.zxpay.sharedkernel.money.Money;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 对通道的能力需求：这一笔支付「需要通道具备什么」。
 *
 * <p>由支付上下文根据支付方式与业务配置推导得出，交给通道上下文做匹配。
 * 放在通道上下文而不是支付上下文，是因为「通道能力」是这个上下文的通用语言，
 * 支付上下文只负责表达意图（我要用 Apple Pay 收 100 美元），
 * 具体需要哪些能力位（NETWORK_TOKENIZATION + AUTH_ONLY + THREE_DS_CHALLENGE）
 * 是通道领域的知识，不该污染支付领域。
 *
 * <p>这个划分避免了两个上下文互相 know too much。
 */
public record CapabilityRequirement(
        /** 期望的支付方式。 */
        PaymentMethod paymentMethod,

        /** 期望的前端交互形态。由终端类型推导（APP 端走 SDK，PC 端走跳转/扫码）。 */
        InteractionMode interactionMode,

        /** 交易金额。 */
        Money amount,

        /** 必需的能力位。通道缺失任一即被排除。 */
        Set<Capability> requiredCapabilities,

        /** 用户身份标识（openid / buyer_id / customer id）是否已具备。 */
        boolean payerIdentityAvailable,

        /** 是否要求通道支持「授权与请款分离」。担保交易、预售等业务为 true。 */
        boolean manualCaptureRequired
) {

    public CapabilityRequirement {
        requiredCapabilities = requiredCapabilities == null
                ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(requiredCapabilities));
    }

    /** 便捷构造：只需支付方式、金额与必需能力。 */
    public static CapabilityRequirement of(PaymentMethod method, InteractionMode mode, Money amount,
                                           Set<Capability> capabilities) {
        return new CapabilityRequirement(method, mode, amount, capabilities, true, false);
    }
}
