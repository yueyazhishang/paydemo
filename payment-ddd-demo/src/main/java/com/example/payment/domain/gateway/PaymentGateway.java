package com.example.payment.domain.gateway;

import com.example.payment.domain.shared.Channel;

/**
 * 支付网关端口（防腐层 Ports & Adapters 之 Port）。
 * 领域层只依赖此接口；各渠道适配器（infrastructure.gateway.*）实现之。
 *
 * <p>契约约定：
 * <ul>
 *   <li>金额一律使用最小货币单位（Money 值对象），单位换算发生在适配器内</li>
 *   <li>回调报文必须先在适配器内完成验签，验签失败抛出 {@link GatewayException}</li>
 *   <li>所有网络异常由适配器翻译为 {@link GatewayException}，不允许渠道 SDK 异常类型外泄</li>
 * </ul>
 */
public interface PaymentGateway {

    /** 本适配器服务的渠道（用于 GatewayRegistry 策略路由） */
    Channel channel();

    /** 统一下单（预下单） */
    GatewayPayResult prepay(GatewayPayRequest request);

    /** 查单兜底：回调未达或状态存疑时主动查询渠道侧状态 */
    GatewayQueryResult query(String paymentId);

    /** 验签并解析异步回调，产出统一回调消息 */
    ChannelCallbackMessage parseCallback(CallbackRequest callbackRequest);

    /** 发起退款 */
    GatewayRefundResult refund(GatewayRefundRequest request);
}
