package com.demo.payment.application.command;

import com.demo.payment.domain.channel.model.PaymentMethodType;
import com.demo.payment.shared.money.Money;

import java.time.Instant;

/**
 * 创建支付单的应用层命令。
 *
 * <p><b>应用层命令 vs 领域对象：</b>
 * 命令是"请求"，领域对象是"状态"。命令可以含技术字段（clientIp、idempotencyKey），
 * 但这些字段不应污染领域模型 —— 领域只关心"谁付多少钱买什么"。
 */
public record CreatePaymentCommand(
        String merchantId,
        String merchantOrderNo,
        Money amount,
        PaymentMethodType paymentMethod,
        String subject,
        String notifyUrl,
        String returnUrl,
        String clientIp,
        String payerId,
        String paymentCredential,
        String idempotencyKey,
        String countryCode,
        String scene,
        Instant expireAt
) {}
