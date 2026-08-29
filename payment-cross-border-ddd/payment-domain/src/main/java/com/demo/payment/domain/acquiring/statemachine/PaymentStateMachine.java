package com.demo.payment.domain.acquiring.statemachine;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 支付单显式状态机。
 *
 * <h3>为什么不用 if-else，而要用显式转换表</h3>
 * <p>支付状态的转换规则会随业务演进不断变复杂（加预授权、加部分退款、加拒付…）。
 * 用 if-else 散落在各个 Service 里，很快就会出现：
 * "为什么这个订单能从 PAID 变成 CLOSED？" —— 没人答得上来，因为规则不可见。
 *
 * <p>显式转换表把<b>全部合法路径集中在一处</b>，任何一次非法跳转都会在
 * 运行期立刻抛异常，而不是悄悄写脏数据。这对资金系统至关重要。
 *
 * <h3>两个必须记住的规则</h3>
 * <ol>
 *   <li><b>终态不可变</b>：REFUNDED / CLOSED / FAILED 一旦进入，拒绝任何变更。
 *       这是防回调乱序的最后一道防线。</li>
 *   <li><b>不存在"回退"</b>：PAID 不能回到 PAYING。用户付完钱，系统不能假装没付。</li>
 * </ol>
 */
public final class PaymentStateMachine {

    private static final Map<PaymentStatus, Set<PaymentStatus>> TRANSITIONS =
            new EnumMap<>(PaymentStatus.class);

    static {
        // 已创建 → 支付中（拿到通道凭证）/ 关闭（下单即取消）/ 失败（通道受理即失败）
        TRANSITIONS.put(PaymentStatus.CREATED, EnumSet.of(
                PaymentStatus.PAYING, PaymentStatus.AUTHORIZED,
                PaymentStatus.CLOSED, PaymentStatus.FAILED));

        // 支付中 → 已支付（一段式）/ 已授权（两段式，先冻结）/ 关闭（超时关单）/ 失败
        TRANSITIONS.put(PaymentStatus.PAYING, EnumSet.of(
                PaymentStatus.PAID, PaymentStatus.AUTHORIZED,
                PaymentStatus.CLOSED, PaymentStatus.FAILED));

        // 已授权 → 请款中 / 已支付（部分通道 capture 同步返回）/ 关闭（撤销授权）/ 失败
        TRANSITIONS.put(PaymentStatus.AUTHORIZED, EnumSet.of(
                PaymentStatus.CAPTURING, PaymentStatus.PAID,
                PaymentStatus.CLOSED, PaymentStatus.FAILED));

        // 请款中 → 已支付 / 失败（请款被拒，额度未冻结住）
        TRANSITIONS.put(PaymentStatus.CAPTURING, EnumSet.of(
                PaymentStatus.PAID, PaymentStatus.FAILED));

        // 已支付 → 部分退款 / 全额退款
        TRANSITIONS.put(PaymentStatus.PAID, EnumSet.of(
                PaymentStatus.PARTIALLY_REFUNDED, PaymentStatus.REFUNDED));

        // 部分退款 → 继续部分退款 / 退完变全额退款
        TRANSITIONS.put(PaymentStatus.PARTIALLY_REFUNDED, EnumSet.of(
                PaymentStatus.PARTIALLY_REFUNDED, PaymentStatus.REFUNDED));

        // 终态：无任何出边
        TRANSITIONS.put(PaymentStatus.REFUNDED, EnumSet.noneOf(PaymentStatus.class));
        TRANSITIONS.put(PaymentStatus.CLOSED, EnumSet.noneOf(PaymentStatus.class));
        TRANSITIONS.put(PaymentStatus.FAILED, EnumSet.noneOf(PaymentStatus.class));
    }

    private PaymentStateMachine() {}

    /**
     * 校验状态转换是否合法。
     *
     * @throws IllegalStateException 非法转换。调用方应记录告警而非重试 ——
     *         反复重试非法转换只会刷日志，掩盖真实问题。
     */
    public static void validate(PaymentStatus from, PaymentStatus to) {
        if (from == to) {
            // 幂等重复通知（同一结果回调两次）是正常现象，静默放过
            return;
        }
        if (from.isTerminal()) {
            throw new IllegalStateException(
                    "Reject transition from terminal status: " + from + " -> " + to
                            + " (likely out-of-order notification, keep original state)");
        }
        Set<PaymentStatus> allowed = TRANSITIONS.getOrDefault(from, EnumSet.noneOf(PaymentStatus.class));
        if (!allowed.contains(to)) {
            throw new IllegalStateException(
                    "Illegal payment status transition: " + from + " -> " + to
                            + ", allowed: " + allowed);
        }
    }

    public static boolean canTransit(PaymentStatus from, PaymentStatus to) {
        if (from == to) { return true; }
        if (from.isTerminal()) { return false; }
        return TRANSITIONS.getOrDefault(from, EnumSet.noneOf(PaymentStatus.class)).contains(to);
    }

    /** 打印全部合法路径，用于文档生成与人工 review */
    public static String describe() {
        StringBuilder sb = new StringBuilder();
        TRANSITIONS.forEach((from, tos) -> {
            if (!tos.isEmpty()) {
                sb.append(from).append(" -> ").append(tos).append('\n');
            }
        });
        return sb.toString();
    }
}
