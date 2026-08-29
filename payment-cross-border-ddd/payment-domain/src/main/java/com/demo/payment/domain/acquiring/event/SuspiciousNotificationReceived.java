package com.demo.payment.domain.acquiring.event;

import com.demo.payment.shared.event.DomainEvent;

import java.time.Instant;

/**
 * 可疑通知事件 —— 资金系统的"黑匣子告警"。
 *
 * <p><b>为什么要有这一类事件？</b>
 * 支付系统里有一类情况，既不该改状态，也不该静默吞掉：
 * <ul>
 *   <li>已支付订单收到失败回调（回调乱序）</li>
 *   <li>回调金额与订单金额不一致（疑似篡改）</li>
 *   <li>订单已关闭却收到成功回调（可能是通道侧延迟扣款）</li>
 *   <li>同一 notifyId 在不同订单上重复出现</li>
 * </ul>
 *
 * <p>这些信号的共同特点是：<b>小额、偶发、但每一次都可能是重大事故的前兆</b>。
 * 如果只记日志，它们会淹没在海量正常日志里；
 * 如果抛异常，又会触发通道重投，把一次告警变成几十次。
 *
 * <p>正确做法是发一个领域事件：
 * <ol>
 *   <li>业务状态保持不变（不因可疑信号破坏已确定的终态）</li>
 *   <li>回调接入层照常返回成功，让通道停止重投</li>
 *   <li>事件流入监控/风控系统，按阈值聚合告警</li>
 * </ol>
 *
 * <p>在生产环境中，这类事件应当被单独订阅并接入：
 * 实时监控看板、风控规则引擎、以及对账差异池。
 */
public record SuspiciousNotificationReceived(
        String aggregateId,
        String merchantOrderNo,
        String outTradeNo,
        /** 可疑类型编码，便于监控按类型聚合 */
        String suspiciousType,
        /** 人类可读的详情，含当时的订单状态快照 */
        String detail,
        Instant occurredAt
) implements DomainEvent {

    /**
     * 预定义的可疑类型。
     *
     * <p>用常量而非散落的字符串，是为了让监控规则可以稳定地按类型匹配，
     * 也避免不同开发者写出 "PAID_ORDER_FAIL" 和 "paid_order_failed" 两种写法
     * 导致告警规则漏配。
     */
    public static final String PAID_ORDER_RECEIVED_FAILURE = "PAID_ORDER_RECEIVED_FAILURE";
    public static final String AMOUNT_MISMATCH = "AMOUNT_MISMATCH";
    public static final String CLOSED_ORDER_RECEIVED_SUCCESS = "CLOSED_ORDER_RECEIVED_SUCCESS";
    public static final String DUPLICATE_NOTIFY_DIFFERENT_ORDER = "DUPLICATE_NOTIFY_DIFFERENT_ORDER";
    public static final String CHANNEL_STATUS_CONTRADICTION = "CHANNEL_STATUS_CONTRADICTION";

    /**
     * 是否为高危类型（需要立即告警，而非仅记录）。
     *
     * <p>金额不一致属于安全事件级别 —— 它意味着有人在构造报文，
     * 可能是攻击探测，必须立即响应。
     */
    public boolean isCritical() {
        return AMOUNT_MISMATCH.equals(suspiciousType);
    }
}
