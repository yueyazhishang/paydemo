package com.zxpay.domain.payment.model;

import com.zxpay.domain.channel.model.InteractionMode;

import java.util.Collections;
import java.util.Map;

/**
 * 前端交互载荷：下单之后，前端需要拿什么去唤起收银台。
 *
 * <p>这是消灭 {@code if (channel == WECHAT) return codeUrl; else if (channel == STRIPE) return clientSecret;}
 * 的关键对象。通道适配器负责把自己家的返回翻译成统一的 {@link ChannelInteraction}，
 * 应用层原样透传给前端，不做任何分支判断。
 *
 * <p>各通道的实际形态：
 * <ul>
 *   <li>微信 JSAPI → {@code FRONTEND_SDK} + prepay_id 及签名参数，前端 {@code wx.chooseWXPay}。</li>
 *   <li>微信 NATIVE → {@code SCAN_QR} + code_url，前端渲染二维码。</li>
 *   <li>支付宝 PC → {@code REDIRECT} + 收银台 URL，浏览器 302。</li>
 *   <li>Stripe → {@code FRONTEND_SDK} + client_secret，前端 Elements 确认。</li>
 *   <li>PayPal → {@code REDIRECT} + approve_url，跳转后用户确认再回调。</li>
 *   <li>Card 直扣 → {@code NONE}，同步即出终态。</li>
 * </ul>
 */
public record ChannelInteraction(
        InteractionMode mode,

        /** 二维码内容（SCAN_QR 模式）。前端渲染成二维码。 */
        String codeUrl,

        /** 跳转 URL（REDIRECT 模式）。浏览器直接 302。 */
        String redirectUrl,

        /** SDK 唤起参数（FRONTEND_SDK 模式）。如微信 prepay 参数、Stripe client_secret。 */
        Map<String, String> sdkParams,

        /** 需要用户线下完成时展示的收款信息（ASYNC_INSTRUCTION 模式）。 */
        String instructionText
) {

    public ChannelInteraction {
        mode = mode == null ? InteractionMode.API_ONLY : mode;
        sdkParams = sdkParams == null ? Map.of() : Collections.unmodifiableMap(sdkParams);
    }

    /** 无前端动作：纯服务端下单，同步出终态（卡支付、已授权的代扣）。 */
    public static ChannelInteraction none() {
        return new ChannelInteraction(InteractionMode.API_ONLY, null, null, Map.of(), null);
    }

    public static ChannelInteraction qrCode(String codeUrl) {
        return new ChannelInteraction(InteractionMode.SCAN_QR, codeUrl, null, Map.of(), null);
    }

    public static ChannelInteraction redirect(String redirectUrl) {
        return new ChannelInteraction(InteractionMode.REDIRECT, null, redirectUrl, Map.of(), null);
    }

    public static ChannelInteraction sdk(InteractionMode mode, Map<String, String> params) {
        return new ChannelInteraction(mode, null, null, params, null);
    }

    public static ChannelInteraction instruction(String text) {
        return new ChannelInteraction(InteractionMode.ASYNC_INSTRUCTION, null, null, Map.of(), text);
    }

    /** 是否需要前端参与。 */
    public boolean requiresFrontendAction() {
        return mode != InteractionMode.API_ONLY;
    }
}
