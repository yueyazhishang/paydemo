package com.zx.payment.shared;

import java.time.Instant;

/**
 * 集成事件（发布语言）：支付成功事实。收单上下文发布，退款/对账等下游订阅。
 *
 * 为什么放在 shared-kernel：它是跨上下文的公共契约，上下游都要引用。
 * 放在任一方都会导致另一方反向依赖，破坏上下文边界。
 *
 * 为什么不含 toPaidFact() 这类翻译方法（v1 设计稿里我写错过一次）：
 *   翻译的目标类型 PaidFact 属于【退款上下文】。如果在这个契约里写 toPaidFact()，
 *   就等于让共享内核依赖下游上下文——依赖方向彻底倒置，上下文边界失效。
 *   正确做法：V1 保持纯数据，由下游自己的防腐层翻译（退款侧 PaymentSucceededTranslator）。
 *   这也符合防腐层的核心原则——谁需要，谁翻译。
 *
 * 契约演进规则：字段只增不改。破坏性变更新建 PaymentSucceededV2，双写过渡一个发布周期。
 */
public record PaymentSucceededV1(
        String eventId,
        Instant occurredAt,
        /** 收单上下文的聚合标识。下游仅作引用，不得解释其内部结构。 */
        String paymentId,
        String merchantId,
        String merchantOrderNo,
        /** 最小货币单位，避免精度歧义。币种单独一个字段，不做隐式换算。 */
        long paidAmountMinor,
        String currency,
        String channelCode,
        String channelTradeNo,
        Instant paidAt
) implements IntegrationEvent {

    public PaymentSucceededV1 {
        if (paymentId == null || paymentId.isBlank()) {
            throw new IllegalArgumentException("paymentId 不能为空");
        }
        if (paidAmountMinor <= 0) {
            throw new IllegalArgumentException("支付金额必须大于 0");
        }
    }

    /** 便利方法：还原为 Money 值对象。注意这是读取，不是翻译——不引入下游概念。 */
    public Money paidAmount() {
        return Money.ofMinor(paidAmountMinor, CurrencyCode.of(currency));
    }

    public ChannelCode channel() {
        return ChannelCode.of(channelCode);
    }
}
