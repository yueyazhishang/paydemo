package com.demo.payment.domain.channel.route;

import com.demo.payment.domain.channel.model.ChannelCapability;
import com.demo.payment.domain.channel.model.ChannelCode;
import com.demo.payment.domain.channel.model.PaymentMethodType;
import com.demo.payment.domain.channel.spi.RoutingContext;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 基于能力矩阵的路由器 —— 默认实现。
 *
 * <p>路由分两阶段：
 * <ol>
 *   <li><b>硬过滤</b>（能力矩阵）：过滤掉不支持该支付方式、币种、金额的通道。
 *       这一步是<b>纯内存判断，零 IO</b>，性能极高。</li>
 *   <li><b>软排序</b>（策略）：按费率、成功率、健康度打分排序。</li>
 * </ol>
 *
 * <p><b>为什么把能力过滤放在领域层而不是配置中心？</b>
 * 因为能力是通道的<b>客观属性</b>（不支持就是不支持），
 * 而费率、权重是<b>运营策略</b>（可以随时调）。
 * 前者写死在代码里由编译期保证，后者放配置中心支持热更新 —— 职责分离。
 */
public class CapabilityBasedRouter implements ChannelRouter {

    private final Map<ChannelCode, ChannelCapability> capabilities = new EnumMap<>(ChannelCode.class);
    private final RouteStrategy strategy;

    public CapabilityBasedRouter(RouteStrategy strategy) {
        this.strategy = Objects.requireNonNull(strategy, "strategy");
    }

    /** 注册通道能力（由 infrastructure 层在启动时装配） */
    public CapabilityBasedRouter register(ChannelCapability capability) {
        capabilities.put(capability.channelCode(), capability);
        return this;
    }

    @Override
    public List<ChannelCode> route(RoutingContext context) {
        Objects.requireNonNull(context, "context");

        // 阶段一：硬过滤
        List<ChannelCode> candidates = capabilities.values().stream()
                .filter(cap -> supports(cap, context))
                .map(ChannelCapability::channelCode)
                .collect(Collectors.toList());

        if (candidates.isEmpty()) {
            return List.of();
        }

        // 阶段二：按策略排序
        return strategy.rank(candidates, context);
    }

    private boolean supports(ChannelCapability cap, RoutingContext ctx) {
        // 规则一：支付方式必须被支持
        if (!cap.supports(ctx.paymentMethod())) {
            return false;
        }
        // 规则二：币种必须被支持
        if (ctx.currency() != null && !cap.supports(ctx.currency())) {
            return false;
        }
        // 规则三：金额必须在通道限额内
        if (ctx.amount() != null && !cap.isAmountInRange(ctx.amount().minorUnits())) {
            return false;
        }
        return true;
    }

    /**
     * 诊断方法：返回每个通道为何被过滤掉。
     *
     * <p><b>这个方法在生产排查中极其有用。</b>
     * "用户说付不了款"时，最需要知道的就是"哪些通道被过滤了、为什么"。
     * 没有它，你只能靠猜。
     */
    public Map<ChannelCode, String> explain(RoutingContext context) {
        Map<ChannelCode, String> result = new LinkedHashMap<>();
        for (ChannelCapability cap : capabilities.values()) {
            if (cap.supports(context.paymentMethod())
                    && (context.currency() == null || cap.supports(context.currency()))
                    && (context.amount() == null || cap.isAmountInRange(context.amount().minorUnits()))) {
                result.put(cap.channelCode(), "AVAILABLE");
            } else if (!cap.supports(context.paymentMethod())) {
                result.put(cap.channelCode(), "不支持支付方式 " + context.paymentMethod());
            } else if (context.currency() != null && !cap.supports(context.currency())) {
                result.put(cap.channelCode(), "不支持币种 " + context.currency().code());
            } else {
                result.put(cap.channelCode(), "金额超出限额");
            }
        }
        return result;
    }

    public Optional<ChannelCapability> capabilityOf(ChannelCode code) {
        return Optional.ofNullable(capabilities.get(code));
    }
}
