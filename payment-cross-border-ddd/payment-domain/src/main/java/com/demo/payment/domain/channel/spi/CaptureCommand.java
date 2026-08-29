package com.demo.payment.domain.channel.spi;

import com.demo.payment.domain.acquiring.model.vo.OutTradeNo;
import com.demo.payment.shared.money.Money;

/**
 * 请款命令（两段式通道的第二步）。
 *
 * <p>典型业务：酒店预授权 —— 入住时先授权冻结 1000 元，退房时按实际消费 800 元请款，
 * 剩余 200 元自动解冻。若按一段式实现，就只能"先扣 1000 再退 200"，
 * 多占用户额度、多付手续费、体验也差。
 *
 * <p><b>部分请款</b>：{@code amount} 小于授权金额时，部分通道会自动释放差额，
 * 部分需要显式调用撤销授权。这是适配层必须处理的差异。
 */
public record CaptureCommand(
        OutTradeNo outTradeNo,
        String channelTransactionId,
        Money amount,
        Money authorizedAmount,
        String idempotencyKey
) {}
