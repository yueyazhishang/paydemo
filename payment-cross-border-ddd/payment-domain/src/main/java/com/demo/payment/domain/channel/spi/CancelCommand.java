package com.demo.payment.domain.channel.spi;

import com.demo.payment.domain.acquiring.model.vo.OutTradeNo;

/**
 * 撤销（void）命令。
 *
 * <p><b>撤销 ≠ 退款，这个区别在资金上非常关键：</b>
 * <table border="1">
 *   <tr><th></th><th>撤销 void</th><th>退款 refund</th></tr>
 *   <tr><td>时机</td><td>清算前（通常当日）</td><td>清算后</td></tr>
 *   <tr><td>资金流</td><td>冻结额度直接释放，<b>未真正划账</b></td><td>已收款再退回</td></tr>
 *   <tr><td>手续费</td><td><b>通常不收取</b></td><td>通常不退手续费</td></tr>
 *   <tr><td>凭证</td><td>不产生独立退款单</td><td>产生独立退款单</td></tr>
 *   <tr><td>国内通道</td><td><b>基本不支持</b></td><td>支持</td></tr>
 * </table>
 *
 * <p>因此 {@code ChannelCapability.supportsCancel} 是路由与退款策略的重要判断依据：
 * 当日撤销优先走 void（省手续费），隔日只能走 refund。
 */
public record CancelCommand(OutTradeNo outTradeNo, String channelTransactionId, String reason) {}
