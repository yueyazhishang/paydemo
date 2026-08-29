package com.demo.payment.domain.channel.spi;

import com.demo.payment.domain.acquiring.model.vo.OutTradeNo;
import com.demo.payment.shared.money.Money;

/**
 * 退款命令。
 *
 * <p><b>为什么需要 outRefundNo？</b>
 * 退款在通道侧是一笔独立的交易，需要独立的幂等标识。
 * 若直接复用 outTradeNo，同一订单多次部分退款就会撞号。
 * outRefundNo 必须在<b>通道维度</b>唯一（不是订单维度）。
 */
public record RefundCommand(
        OutTradeNo outTradeNo,
        String outRefundNo,
        Money amount,
        Money originalAmount,
        String reason,
        String notifyUrl,
        String idempotencyKey
) {}
