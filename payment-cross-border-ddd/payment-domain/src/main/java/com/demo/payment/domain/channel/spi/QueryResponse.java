package com.demo.payment.domain.channel.spi;

import com.demo.payment.domain.acquiring.model.vo.OutTradeNo;
import com.demo.payment.domain.channel.model.ChannelResultStatus;
import com.demo.payment.shared.money.Money;

/**
 * 查证响应。
 *
 * <p><b>查证是支付系统的定海神针。</b>
 * 所有异步通知都只是"触发器"，真正决定订单终态的是查证结果。
 * 生产环境必须部署查证补偿任务：对超过 N 分钟仍处于"支付中"的订单逐级轮询
 * （10s / 30s / 60s / 5min / 30min / 2h），直到拿到终态或超过通道查询窗口。
 */
public record QueryResponse(
        OutTradeNo outTradeNo,
        ChannelResultStatus status,
        String channelTransactionId,
        String channelRawStatus,
        Money amount,
        String message,
        boolean infrastructureError
) {
    /**
     * 查证时通道明确返回"订单不存在"。
     *
     * <p><b>注意：这不等于支付失败！</b>
     * 下单请求可能根本没到达通道（网络在请求阶段就断了），
     * 此时查单必然返回 NOT_EXIST。正确处理是：
     * 若距下单时间已超过通道的订单创建延迟窗口（通常 30s~5min），
     * 才判定为失败；否则继续等待重试。
     */
    public boolean isOrderNotExist() {
        return "NOT_EXIST".equals(channelRawStatus) || "ORDER_NOT_EXIST".equals(channelRawStatus);
    }

    public static QueryResponse of(OutTradeNo no, ChannelResultStatus status,
                                   String txId, Money amount) {
        return new QueryResponse(no, status, txId, status.name(), amount, null, false);
    }

    public static QueryResponse unknown(OutTradeNo no, String message) {
        return new QueryResponse(no, ChannelResultStatus.UNKNOWN, null, null, null, message, true);
    }
}
