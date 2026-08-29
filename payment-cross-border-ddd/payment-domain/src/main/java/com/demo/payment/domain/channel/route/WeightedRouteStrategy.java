package com.demo.payment.domain.channel.route;

import com.demo.payment.domain.channel.model.ChannelCode;
import com.demo.payment.domain.channel.spi.RoutingContext;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 加权路由策略：费率 + 健康度 + 静态权重 综合打分。
 *
 * <p>打分公式（简化版）：
 * <pre>
 *   score = w1 * (1 - normalizedFeeRate) * 100
 *         + w2 * healthScore
 *         + w3 * staticWeight
 * </pre>
 *
 * <p><b>注意：真实系统的成功率统计必须是滑动窗口的。</b>
 * 用全量历史成功率会导致"强者恒强"——新通道永远拿不到流量，
 * 也就永远无法证明自己。通常做法是保留 5%~10% 的探索流量给新通道。
 */
public class WeightedRouteStrategy implements RouteStrategy {

    /** 费率权重 */
    private final double feeWeight;
    /** 健康度权重 */
    private final double healthWeight;
    /** 静态权重 */
    private final double staticWeight;

    private final Map<ChannelCode, ChannelHealth> health = new EnumMap<>(ChannelCode.class);
    private final Map<ChannelCode, Double> feeRates = new EnumMap<>(ChannelCode.class);
    private final Map<ChannelCode, Integer> staticWeights = new EnumMap<>(ChannelCode.class);

    public WeightedRouteStrategy() {
        this(0.4, 0.4, 0.2);
    }

    public WeightedRouteStrategy(double feeWeight, double healthWeight, double staticWeight) {
        this.feeWeight = feeWeight;
        this.healthWeight = healthWeight;
        this.staticWeight = staticWeight;
        initDefaults();
    }

    private void initDefaults() {
        // 费率：示例值，实际应由运营配置中心下发
        feeRates.put(ChannelCode.WECHAT_PAY, 0.006);
        feeRates.put(ChannelCode.ALIPAY, 0.006);
        feeRates.put(ChannelCode.JD_PAY, 0.007);
        feeRates.put(ChannelCode.UNION_PAY, 0.0055);
        feeRates.put(ChannelCode.PAYPAL, 0.029);
        feeRates.put(ChannelCode.STRIPE, 0.029);
        feeRates.put(ChannelCode.WORLDPAY, 0.0275);
        feeRates.put(ChannelCode.ANTOM, 0.025);

        for (ChannelCode code : ChannelCode.values()) {
            health.put(code, new ChannelHealth());
            staticWeights.put(code, 50);
        }
    }

    @Override
    public List<ChannelCode> rank(List<ChannelCode> candidates, RoutingContext context) {
        return candidates.stream()
                .map(code -> new Scored(code, score(code)))
                .sorted(Comparator.comparingDouble(Scored::score).reversed())
                .map(Scored::code)
                .collect(Collectors.toList());
    }

    private double score(ChannelCode code) {
        double fee = feeRates.getOrDefault(code, 0.03);
        // 费率归一化：假设最高 3%，越低越好
        double feeScore = Math.max(0, (1 - fee / 0.03)) * 100;
        double healthScore = health.getOrDefault(code, new ChannelHealth()).score();
        double staticScore = staticWeights.getOrDefault(code, 50);
        return feeWeight * feeScore + healthWeight * healthScore + staticWeight * staticScore;
    }

    /** 记录通道调用结果，用于健康度统计与熔断 */
    public void record(ChannelCode code, boolean success) {
        health.computeIfAbsent(code, k -> new ChannelHealth()).record(success);
    }

    /** 通道是否已被熔断 */
    public boolean isCircuitOpen(ChannelCode code) {
        ChannelHealth h = health.get(code);
        return h != null && h.isCircuitOpen();
    }

    private record Scored(ChannelCode code, double score) {}

    /**
     * 通道健康度 —— 滑动窗口统计 + 熔断。
     *
     * <p>熔断状态机：CLOSED(正常) → OPEN(熔断，流量全部摘除) → HALF_OPEN(半开，放少量探测流量)
     */
    public static final class ChannelHealth {
        private static final int WINDOW_SIZE = 100;
        private final Deque<Boolean> window = new ArrayDeque<>();
        private int consecutiveFailures = 0;
        private long openedAt = 0L;

        /** 连续失败达到此阈值则熔断 */
        private static final int CIRCUIT_BREAK_THRESHOLD = 10;
        /** 熔断后冷却时间（毫秒） */
        private static final long COOLDOWN_MS = 30_000L;

        public synchronized void record(boolean success) {
            window.addLast(success);
            if (window.size() > WINDOW_SIZE) {
                window.removeFirst();
            }
            if (success) {
                consecutiveFailures = 0;
                openedAt = 0L;
            } else {
                consecutiveFailures++;
                if (consecutiveFailures >= CIRCUIT_BREAK_THRESHOLD && openedAt == 0L) {
                    openedAt = System.currentTimeMillis();
                }
            }
        }

        /** 滑动窗口成功率（0~100） */
        public synchronized double score() {
            if (window.isEmpty()) {
                return 100.0;
            }
            long ok = window.stream().filter(Boolean::booleanValue).count();
            return ok * 100.0 / window.size();
        }

        public synchronized boolean isCircuitOpen() {
            if (openedAt == 0L) {
                return false;
            }
            // 冷却期结束后自动进入半开：先认为未熔断，让少量流量进来探测
            if (System.currentTimeMillis() - openedAt > COOLDOWN_MS) {
                openedAt = 0L;
                consecutiveFailures = 0;
                return false;
            }
            return true;
        }
    }
}
