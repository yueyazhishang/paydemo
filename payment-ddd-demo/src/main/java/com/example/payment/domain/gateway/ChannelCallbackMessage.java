package com.example.payment.domain.gateway;

import lombok.Builder;
import lombok.Getter;

/**
 * 渠道回调统一解析结果（防腐层的核心产出物之一）。
 * 验签未通过时 parseCallback 直接抛出异常，签名通过才构造本对象，
 * 领域层可以完全信任 signVerified = true 的结果。
 */
@Getter
@Builder
public class ChannelCallbackMessage {

    private final CallbackType callbackType;

    /** 我方单号（支付单号或退款单号，视 callbackType 而定） */
    private final String ourTradeNo;

    /** 渠道流水号 */
    private final String channelTradeNo;

    /** 交易是否成功 */
    private final boolean success;

    /** 成功金额（用于与订单金额核对，防串单/篡改） */
    private final Long amountMinor;

    private final boolean signVerified;

    /** 原始报文（留痕用） */
    private final String rawBody;
}
