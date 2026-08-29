package com.demo.payment.domain.channel.spi;

import com.demo.payment.domain.acquiring.model.vo.OutTradeNo;

/**
 * 查证命令。
 *
 * <p><b>为什么查证接口要用 outTradeNo 而非 channelTransactionId？</b>
 * 因为下单超时的场景下，我们根本没拿到 channelTransactionId。
 * 查证必须支持"只用我方订单号查"，否则超时场景无法闭环 ——
 * 这正是 UNKNOWN 状态必须由查证兜底的原因。
 */
public record QueryCommand(OutTradeNo outTradeNo, String channelTransactionId) {

    public static QueryCommand byOutTradeNo(OutTradeNo outTradeNo) {
        return new QueryCommand(outTradeNo, null);
    }
}
