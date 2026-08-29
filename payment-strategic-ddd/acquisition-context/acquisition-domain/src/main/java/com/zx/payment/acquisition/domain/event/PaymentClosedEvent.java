package com.zx.payment.acquisition.domain.event;

import com.zx.payment.shared.DomainEvent;
import com.zx.payment.shared.Money;

/**
 * 领域事件：支付单已关闭（超时未付清 / 商户主动取消）。
 *
 * 关键订阅方：如果关闭时已有部分收款（PARTIAL），下游必须触发【自动退款】流程——
 * 用户付了一半被关单，钱必须退回去。这是很容易漏掉的资损点。
 */
public final class PaymentClosedEvent extends DomainEvent {

    private final String merchantOrderNo;
    private final String reason;
    private final Money receivedAmount;

    public PaymentClosedEvent(String paymentId, String merchantOrderNo, String reason,
                              Money receivedAmount) {
        super(paymentId);
        this.merchantOrderNo = merchantOrderNo;
        this.reason = reason;
        this.receivedAmount = receivedAmount;
    }

    public String merchantOrderNo() { return merchantOrderNo; }
    public String reason() { return reason; }

    /** 关闭时已收到的金额。大于 0 表示需要自动退款。 */
    public Money receivedAmount() { return receivedAmount; }
}
