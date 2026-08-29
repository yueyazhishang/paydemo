package com.demo.payment.domain.channel.spi;

import com.demo.payment.domain.acquiring.model.vo.OutTradeNo;

public record CancelResponse(
        OutTradeNo outTradeNo,
        boolean cancelled,
        String code,
        String message,
        boolean infrastructureError
) {
    public static CancelResponse success(OutTradeNo no) {
        return new CancelResponse(no, true, null, null, false);
    }

    public static CancelResponse fail(OutTradeNo no, String code, String message) {
        return new CancelResponse(no, false, code, message, false);
    }
}
