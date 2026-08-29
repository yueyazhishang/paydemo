package com.demo.payment.domain.channel.spi;

import com.demo.payment.domain.acquiring.model.vo.OutTradeNo;

public record CloseResponse(
        OutTradeNo outTradeNo,
        boolean closed,
        String code,
        String message,
        boolean infrastructureError
) {
    public static CloseResponse success(OutTradeNo no) {
        return new CloseResponse(no, true, null, null, false);
    }

    public static CloseResponse fail(OutTradeNo no, String code, String message) {
        return new CloseResponse(no, false, code, message, false);
    }
}
