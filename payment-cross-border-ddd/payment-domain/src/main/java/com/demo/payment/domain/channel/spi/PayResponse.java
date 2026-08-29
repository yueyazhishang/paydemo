package com.demo.payment.domain.channel.spi;

import com.demo.payment.domain.acquiring.model.vo.OutTradeNo;
import com.demo.payment.domain.channel.model.ChannelResultStatus;

import java.util.HashMap;
import java.util.Map;

/**
 * 支付受理响应。
 *
 * <p><b>最容易误解的一点：{@code status == PENDING} 不代表失败。</b>
 * 国内通道下单后返回的是"支付凭证"（prepay_id / code_url），
 * 用户还要在 App 里完成付款。此时订单处于 PENDING 才是正常状态。
 * 很多新手看到没返回"成功"就判定失败并关单，结果用户正在输入密码时订单被关掉了。
 *
 * <p>各通道的凭证形态差异极大，全部收敛到 {@code credential} 这一组 Map 里：
 * <pre>
 *   微信 JSAPI  →  prepayId, nonceStr, timestamp, paySign, package
 *   微信 Native →  codeUrl
 *   支付宝 APP  →  orderString（可直接唤起 App 的串）
 *   Stripe      →  clientSecret（前端 confirm 用）
 *   PayPal      →  approvalUrl（跳转链接） + orderId
 *   Antom       →  paymentSessionData / redirectUrl / normalUrl
 *   Worldpay    →  orderCode + mac（跳转）
 * </pre>
 */
public record PayResponse(
        OutTradeNo outTradeNo,
        ChannelResultStatus status,
        String channelTransactionId,
        String channelRawStatus,
        String code,
        String message,

        /**
         * 支付凭证，用于前端拉起支付。键的含义见各通道适配器文档。
         */
        Map<String, String> credential,

        /** 是否为通道基础设施故障（true 表示可重试/切通道） */
        boolean infrastructureError
) {
    public PayResponse {
        if (credential == null) {
            credential = new HashMap<>();
        }
    }

    public static PayResponse pending(OutTradeNo outTradeNo, Map<String, String> credential) {
        return new PayResponse(outTradeNo, ChannelResultStatus.PENDING, null, null,
                null, null, credential, false);
    }

    public static PayResponse succeeded(OutTradeNo outTradeNo, String channelTxId) {
        return new PayResponse(outTradeNo, ChannelResultStatus.SUCCEEDED, channelTxId,
                "SUCCESS", null, null, Map.of(), false);
    }

    public static PayResponse failed(OutTradeNo outTradeNo, String code, String message) {
        return new PayResponse(outTradeNo, ChannelResultStatus.FAILED, null, "FAILED",
                code, message, Map.of(), false);
    }

    /**
     * 结果未知 —— 网络超时等场景。
     * <b>返回此值时，上层必须保持订单为"支付中"并发起查证，绝不能关单。</b>
     */
    public static PayResponse unknown(OutTradeNo outTradeNo, String message) {
        return new PayResponse(outTradeNo, ChannelResultStatus.UNKNOWN, null, null,
                "UNKNOWN", message, Map.of(), true);
    }

    public static PayResponse infraError(OutTradeNo outTradeNo, String message) {
        return new PayResponse(outTradeNo, ChannelResultStatus.UNKNOWN, null, null,
                "INFRA_ERROR", message, Map.of(), true);
    }

    public boolean isPending() { return status == ChannelResultStatus.PENDING; }
    public boolean isSucceeded() { return status == ChannelResultStatus.SUCCEEDED; }
    public boolean isUnknown() { return status == ChannelResultStatus.UNKNOWN; }
}
