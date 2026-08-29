package com.example.payment.application.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/**
 * 发起退款命令。
 */
@Data
public class RefundCommand {

    /** 原支付单号 */
    @NotBlank
    private String paymentId;

    /** 退款金额：最小货币单位，支持部分退款 */
    @NotNull
    @Positive
    private Long refundAmountMinor;

    @NotBlank
    private String currency;

    private String reason;
}
