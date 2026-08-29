package com.example.payment.domain.gateway;

import lombok.Builder;
import lombok.Getter;

/**
 * 渠道账单统一记录（对账 Published Language）。
 */
@Getter
@Builder
public class BillRecord {

    public enum BillType { PAY, REFUND }

    private final BillType type;

    /** 我方业务单号（渠道账单回传的商户单号字段） */
    private final String ourTradeNo;

    private final String channelTradeNo;

    /** 金额（最小货币单位） */
    private final long amountMinor;

    /** 渠道侧交易时间 */
    private final String tradeTime;
}
