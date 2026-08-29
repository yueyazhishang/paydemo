package com.zx.payment.refund.application.acl;

import com.zx.payment.refund.domain.model.PaidFact;
import com.zx.payment.shared.PaymentSucceededV1;

/**
 * 防腐层（入站翻译）：把收单上下文的集成事件翻译成退款上下文的语言。
 *
 * 为什么翻译器在【下游】而不是上游：
 *   防腐层的核心原则是"谁需要，谁翻译"。
 *   如果让收单上下文提供 toPaidFact() 方法，它就得知道 PaidFact 这个类型，
 *   等于上游依赖下游，依赖方向倒置，上下文边界立刻失效。
 *   （v1 的战略设计稿里我犯过这个错，代码里纠正了。）
 *
 * 防腐层的价值在这里体现得很直接：
 *   收单上下文哪天重构——比如把 paymentId 拆成 tenantId + seqNo，
 *   或者 PaymentSucceededV1 升级到 V2——改动都被挡在这个类里，
 *   退款上下文的领域模型（Refund / PaidFact）一个字都不用改。
 */
public final class PaymentSucceededTranslator {

    private PaymentSucceededTranslator() {
    }

    /**
     * 翻译为支付事实。
     *
     * @param event 收单上下文投递的集成事件（只读契约）
     * @return 退款上下文自己的模型
     */
    public static PaidFact toPaidFact(PaymentSucceededV1 event) {
        if (event == null) {
            throw new IllegalArgumentException("集成事件不能为空");
        }
        return new PaidFact(
                event.paymentId(),
                event.merchantId(),
                event.merchantOrderNo(),
                event.paidAmount(),
                event.channel(),
                event.channelTradeNo(),
                event.paidAt());
    }
}
