package com.zxpay.domain.payment.service;

import com.zxpay.domain.payment.model.PaymentStatus;
import com.zxpay.sharedkernel.exception.DomainException;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * 支付状态机：<b>所有合法状态转移的唯一定义处</b>。
 *
 * <p>为什么要集中管理，而不是在各处 {@code if (status == X) status = Y}：
 * <ol>
 *   <li><b>可审计</b>。一张表看完「哪些转移允许、哪些禁止」，
 *       代码评审时能一眼发现「已支付订单竟然允许被关闭」这类致命漏洞。</li>
 *   <li><b>天然防御乱序通知</b>。通道通知会乱序：先到「成功」后到「失败」，
 *       或重试的旧通知后于新通知到达。有了状态机，
 *       非法的转移直接被拒，而不是把已成功的订单覆盖成失败——
 *       这是支付系统最经典的事故场景之一。</li>
 *   <li><b>终态不可逆</b>。{@code CLOSED / FAILED / REFUNDED} 一旦进入就不再允许任何转移，
 *       从机制上杜绝「用户已付款、订单却被关掉」。</li>
 * </ol>
 *
 * <p>注意 <code>from == to</code> 视为合法（幂等）：通道重复投递同一条通知时，
 * 重复应用相同状态不应报错，否则重试通知会刷出大量异常。
 */
public final class PaymentStateMachine {

    private static final Map<PaymentStatus, Set<PaymentStatus>> TRANSITIONS = buildTransitions();

    private PaymentStateMachine() {
    }

    private static Map<PaymentStatus, Set<PaymentStatus>> buildTransitions() {
        Map<PaymentStatus, Set<PaymentStatus>> map = new EnumMap<>(PaymentStatus.class);

        map.put(PaymentStatus.CREATED, Set.of(
                PaymentStatus.ROUTING,
                PaymentStatus.CLOSED          // 商户在路由前主动关单
        ));

        map.put(PaymentStatus.ROUTING, Set.of(
                PaymentStatus.PAYING,         // 选中通道，发起首次尝试
                PaymentStatus.FAILED,         // 无可用通道
                PaymentStatus.CLOSED
        ));

        map.put(PaymentStatus.PAYING, Set.of(
                PaymentStatus.USERPAYING,     // 付款码支付：等待用户确认
                PaymentStatus.AUTHORIZED,     // 海外 auth 模式
                PaymentStatus.SUCCEEDED,
                PaymentStatus.FAILED,
                PaymentStatus.CLOSED          // 超时未支付
        ));

        map.put(PaymentStatus.USERPAYING, Set.of(
                PaymentStatus.PAYING,         // 用户取消确认，回到支付中
                PaymentStatus.SUCCEEDED,
                PaymentStatus.FAILED,
                PaymentStatus.CLOSED
        ));

        map.put(PaymentStatus.AUTHORIZED, Set.of(
                PaymentStatus.CAPTURING,      // 发起请款
                PaymentStatus.SUCCEEDED,      // 自动请款成功
                PaymentStatus.FAILED,         // 授权过期或被拒
                PaymentStatus.CLOSED          // 撤销授权(VOID)后关闭
        ));

        map.put(PaymentStatus.CAPTURING, Set.of(
                PaymentStatus.SUCCEEDED,
                PaymentStatus.FAILED,
                PaymentStatus.AUTHORIZED      // 请款失败但未超授权期，可重新请款
        ));

        // 已支付不能关闭：要终止必须走退款，否则形成账务黑洞
        map.put(PaymentStatus.SUCCEEDED, Set.of(
                PaymentStatus.REFUNDING,
                PaymentStatus.PARTIAL_REFUNDED
        ));

        map.put(PaymentStatus.REFUNDING, Set.of(
                PaymentStatus.SUCCEEDED,      // 退款失败，退回已支付态
                PaymentStatus.PARTIAL_REFUNDED,
                PaymentStatus.REFUNDED
        ));

        map.put(PaymentStatus.PARTIAL_REFUNDED, Set.of(
                PaymentStatus.REFUNDING,      // 继续退剩余金额
                PaymentStatus.REFUNDED        // 退完最后一部分
        ));

        // 终态：不允许任何转移
        map.put(PaymentStatus.FAILED, Set.of());
        map.put(PaymentStatus.CLOSED, Set.of());
        map.put(PaymentStatus.REFUNDED, Set.of());

        return Collections.unmodifiableMap(map);
    }

    public static boolean canTransit(PaymentStatus from, PaymentStatus to) {
        if (from == null || to == null) {
            return false;
        }
        if (from == to) {
            return true;   // 幂等：重复应用同一状态不算非法
        }
        return TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    /** 校验转移合法性，不合法直接抛领域异常。 */
    public static void requireTransition(PaymentStatus from, PaymentStatus to) {
        if (!canTransit(from, to)) {
            throw new DomainException("PAYMENT_STATUS_TRANSITION_ILLEGAL",
                    "illegal payment status transition: " + from + " -> " + to);
        }
    }

    public static Set<PaymentStatus> allowedNext(PaymentStatus from) {
        return TRANSITIONS.getOrDefault(from, Set.of());
    }

    public static boolean isTerminal(PaymentStatus status) {
        return status != null && status.isTerminal();
    }
}
