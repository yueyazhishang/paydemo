package com.example.payment.domain.gateway;

import com.example.payment.domain.shared.Channel;
import lombok.Builder;
import lombok.Getter;

import java.util.Map;

/**
 * 渠道回调原始报文（未解析、未验签）。
 * 接口层将 HTTP 请求原样包装后传入，验签与解析职责完全在渠道适配器内。
 */
@Getter
@Builder
public class CallbackRequest {

    /** 回调所属渠道 */
    private final Channel channel;

    private final Map<String, String> headers;

    /** 原始请求体（微信 JSON / 支付宝 form 串 / Stripe event JSON / Worldpay XML...） */
    private final String body;
}
