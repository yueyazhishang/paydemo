package com.example.payment.domain.gateway;

import lombok.Builder;
import lombok.Getter;

/**
 * 统一预下单结果。payType + payData 是前端收银台所需的统一二元组。
 */
@Getter
@Builder
public class GatewayPayResult {

    private final boolean success;

    private final PayType payType;

    /** 收银台内容：二维码串 / URL / JSAPI JSON 串 */
    private final String payData;

    /** 渠道侧流水号（部分渠道预下单即返回，如 Stripe PaymentIntent id） */
    private final String channelTradeNo;

    private final String errorMessage;

    public static GatewayPayResult ok(PayType payType, String payData, String channelTradeNo) {
        return GatewayPayResult.builder()
                .success(true).payType(payType).payData(payData).channelTradeNo(channelTradeNo)
                .build();
    }

    public static GatewayPayResult fail(String errorMessage) {
        return GatewayPayResult.builder().success(false).errorMessage(errorMessage).build();
    }
}
