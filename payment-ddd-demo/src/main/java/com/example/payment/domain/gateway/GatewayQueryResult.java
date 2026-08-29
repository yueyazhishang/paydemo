package com.example.payment.domain.gateway;

import com.example.payment.domain.shared.Money;
import lombok.Builder;
import lombok.Getter;

/**
 * 统一查单结果（查单兜底，防丢单边账）。
 */
@Getter
@Builder
public class GatewayQueryResult {

    private final ChannelTradeStatus status;

    private final String channelTradeNo;

    /** 实付金额（渠道回传，用于校验金额一致性） */
    private final Money paidAmount;

    public static GatewayQueryResult success(String channelTradeNo, Money paidAmount) {
        return GatewayQueryResult.builder()
                .status(ChannelTradeStatus.SUCCESS).channelTradeNo(channelTradeNo).paidAmount(paidAmount)
                .build();
    }
}
