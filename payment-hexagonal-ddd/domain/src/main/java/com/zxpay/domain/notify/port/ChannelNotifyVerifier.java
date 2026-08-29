package com.zxpay.domain.notify.port;

import com.zxpay.domain.notify.model.NotificationEnvelope;

/**
 * 出站端口：回调验签。
 *
 * <p><b>这是支付系统最不该被省略的一个接口。</b>
 *
 * <p>回调地址是公开可访问的 URL。任何人只要知道订单号，
 * 就能构造一个「支付成功」的 HTTP 请求打过来。
 * 不验签的回调处理，等于把「标记订单已支付」的权限开放给公网——
 * 攻击者可以零成本提货。
 *
 * <p>各通道验签方式：
 * <ul>
 *   <li>微信支付 APIv3：用平台证书验证 {@code Wechatpay-Signature} 头
 *       （RSA-SHA256  over timestamp+nonce+body），且平台证书需定期轮换。</li>
 *   <li>支付宝：用支付宝公钥验证报文中的 {@code sign} 字段（RSA2）。</li>
 *   <li>Stripe：用 webhook signing secret 计算 HMAC-SHA256，
 *       比对 {@code Stripe-Signature} 头，并校验时间戳防重放。</li>
 *   <li>PayPal：需要回调用 {@code /v1/notifications/verify-webhook-signature}
 *       接口向 PayPal 反向确认（不能本地验）。</li>
 * </ul>
 *
 * <p>注意 PayPal 这类「需要回调通道才能验签」的情况：
 * 这是出站端口，不是纯本地计算，因此设计为接口而非工具类。
 */
public interface ChannelNotifyVerifier {

    /**
     * 验签。
     *
     * @return 验签结果。{@code passed} 为 true 才允许继续处理业务。
     */
    VerifyOutcome verify(NotificationEnvelope envelope);

    /**
     * 验签结果。
     *
     * @param passed     是否通过
     * @param reason     未通过的原因（不通过时必填，用于安全审计）
     * @param replayable 是否疑似重放攻击（时间戳超出容忍窗口）
     */
    record VerifyOutcome(boolean passed, String reason, boolean replayable) {

        public static VerifyOutcome verified() {
            return new VerifyOutcome(true, null, false);
        }

        public static VerifyOutcome invalid(String reason) {
            return new VerifyOutcome(false, reason, false);
        }

        public static VerifyOutcome replaySuspected(String reason) {
            return new VerifyOutcome(false, reason, true);
        }
    }
}
