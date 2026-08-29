package com.example.payment.domain.payment.model;

/**
 * 金额不一致领域异常：回调/查单确认金额与订单应付金额不符（防串单/篡改的不变量被破坏）。
 */
public class AmountMismatchException extends RuntimeException {

    public AmountMismatchException(String message) {
        super(message);
    }
}
