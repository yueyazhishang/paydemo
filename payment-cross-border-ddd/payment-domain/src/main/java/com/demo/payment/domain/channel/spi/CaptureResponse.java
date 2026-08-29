package com.demo.payment.domain.channel.spi;

import com.demo.payment.domain.channel.model.ChannelResultStatus;
import com.demo.payment.shared.money.Money;

public record CaptureResponse(
        String outTradeNo,
        ChannelResultStatus status,
        String channelTransactionId,
        Money capturedAmount,
        String code,
        String message,
        boolean infrastructureError
) {
    public static CaptureResponse succeeded(String outTradeNo, String txId, Money amount) {
        return new CaptureResponse(outTradeNo, ChannelResultStatus.SUCCEEDED,
                txId, amount, null, null, false);
    }

    public static CaptureResponse failed(String outTradeNo, String code, String message) {
        return new CaptureResponse(outTradeNo, ChannelResultStatus.FAILED, null,
                null, code, message, false);
    }
}
