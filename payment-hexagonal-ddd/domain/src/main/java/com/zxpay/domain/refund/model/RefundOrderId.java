package com.zxpay.domain.refund.model;

import com.zxpay.sharedkernel.id.TypedId;

/**
 * 退款单标识。
 *
 * <p>独立聚合的标识——退款单不嵌在支付单里。
 *
 * <p>为什么退款要独立成聚合：见 {@code PaymentOrder} 类注释。
 * 一句话版：维持退款一致性只需要占用一个「已退金额」数值，
 * 不需要把整个退款历史装箱进支付单。若强行内嵌，
 * 支付单会随退款次数线性膨胀，且每次退款都要锁定整个支付单，
 * 并发退款全部串行化。
 */
public final class RefundOrderId extends TypedId {

    private static final String PREFIX = "RFD";

    public RefundOrderId(String value) {
        super(value);
    }

    public static RefundOrderId generate() {
        return new RefundOrderId(generate(PREFIX));
    }

    public static RefundOrderId of(String value) {
        return new RefundOrderId(value);
    }
}
