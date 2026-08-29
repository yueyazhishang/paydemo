package com.zxpay.domain.payment.model;

import com.zxpay.sharedkernel.id.TypedId;

/**
 * 支付尝试标识。
 *
 * <p>一次支付单可能对应多次「尝试」：首次走通道 A 失败，切到通道 B 再试。
 * 每次尝试都要独立记录，原因有三：
 * <ul>
 *   <li><b>幂等</b>：每次尝试携带独立的通道幂等键，重试必须复用同一个键。</li>
 *   <li><b>对账</b>：失败通道上可能实际已扣款（下单成功但结果未知），
 *       只有逐笔尝试留痕，才能在差错处理时定位到那笔「悬空扣款」。</li>
 *   <li><b>分析</b>：通道成功率、切换原因、耗时分布，都依赖尝试级数据。</li>
 * </ul>
 *
 * <p>一个常见错误是「支付失败就直接改状态、丢弃旧通道信息」，
 * 结果就是那句经典的线上问题：<b>钱扣了，订单是失败的</b>。
 */
public final class PaymentAttemptId extends TypedId {

    private static final String PREFIX = "ATT";

    public PaymentAttemptId(String value) {
        super(value);
    }

    public static PaymentAttemptId generate() {
        return new PaymentAttemptId(generate(PREFIX));
    }

    public static PaymentAttemptId of(String value) {
        return new PaymentAttemptId(value);
    }
}
