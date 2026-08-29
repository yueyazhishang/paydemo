package com.yueyazhishang.paydemo.payment.application;

import java.math.BigDecimal;

public class CreatePaymentCommand {
    private String orderId;
    private BigDecimal amount;
    private String currency;
    private String paymentMethod; // STRIPE, PAYPAL, WECHAT, ALIPAY, JD

    public CreatePaymentCommand() {
    }

    public CreatePaymentCommand(String orderId, BigDecimal amount, String currency, String paymentMethod) {
        this.orderId = orderId;
        this.amount = amount;
        this.currency = currency;
        this.paymentMethod = paymentMethod;
    }

    public String getOrderId() {
        return orderId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getPaymentMethod() {
        return paymentMethod;
    }
}
