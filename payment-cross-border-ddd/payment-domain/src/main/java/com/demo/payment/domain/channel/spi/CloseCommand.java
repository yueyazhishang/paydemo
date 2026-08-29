package com.demo.payment.domain.channel.spi;

import com.demo.payment.domain.acquiring.model.vo.OutTradeNo;

/**
 * 关单命令。
 *
 * <p>关单只应作用于<b>未支付</b>的订单。对已支付订单，通道会拒绝关单
 * （这是通道侧提供的一道保护），但本系统仍在聚合根层面做了前置拦截，
 * 避免无谓的通道调用，也避免"关单成功"的假象误导运营。
 */
public record CloseCommand(OutTradeNo outTradeNo, String reason) {}
