package com.zxpay.domain.payment.model;

import com.zxpay.sharedkernel.id.TypedId;

/**
 * 支付单标识（平台侧主键）。
 *
 * <p>注意与「商户订单号」区分：
 * <ul>
 *   <li>{@code merchantOrderNo} 是<b>商户系统的</b>单号，由商户保证在同一应用内唯一，
 *       也是我们做业务幂等的依据。</li>
 *   <li>{@code PaymentOrderId} 是<b>我们平台生成的</b>，全局唯一，
 *       对外暴露给通道、写入对账文件、用于客服查询。</li>
 * </ul>
 * 两者不可混用：商户订单号在极端情况下会被商户复用（换了业务线重新计数），
 * 拿它当平台主键会导致跨商户数据串号。
 */
public final class PaymentOrderId extends TypedId {

    private static final String PREFIX = "PAY";

    public PaymentOrderId(String value) {
        super(value);
    }

    public static PaymentOrderId generate() {
        return new PaymentOrderId(generate(PREFIX));
    }

    public static PaymentOrderId of(String value) {
        return new PaymentOrderId(value);
    }
}
