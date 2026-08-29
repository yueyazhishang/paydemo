package com.zxpay.infrastructure.channel.adapter;

import com.zxpay.domain.channel.model.ChannelCode;
import com.zxpay.domain.payment.model.ChannelRequest;
import com.zxpay.domain.payment.model.ChannelResult;
import com.zxpay.domain.payment.model.FailureInfo;
import com.zxpay.domain.payment.model.PaymentStatus;
import com.zxpay.domain.payment.port.ChannelCapturePort;
import com.zxpay.domain.payment.port.ChannelClosePort;
import com.zxpay.domain.payment.port.ChannelVoidPort;
import com.zxpay.domain.refund.model.ChannelRefundResult;
import com.zxpay.domain.refund.port.ChannelRefundPort;
import com.zxpay.domain.refund.port.ChannelRefundQueryPort;
import com.zxpay.sharedkernel.time.ClockHolder;

import java.time.Instant;
import java.util.Map;

/**
 * Antom（蚂蚁国际）适配器。
 *
 * <p>最值得研究的一家：<b>它是「国内技术栈 + 海外业务语义」的混合体</b>。
 *
 * <ul>
 *   <li>签名用 RSA2 公私钥（支付宝一脉相承的习惯），
 *       而不是 Stripe 的 API Key 或 PayPal 的 OAuth2。</li>
 *   <li>但业务语义完全海外化：3DS 挑战、授权请款分离、
 *       展示币种与结算币种分离、争议处理。</li>
 *   <li>同时支持国际卡与本地化支付方式（东南亚钱包、支付宝钱包），
 *       这类「本地化支付方式」在国内几乎没有对应物。</li>
 * </ul>
 *
 * <p>正因为它横跨两套体系，最能说明一件事：
 * <b>「通道差异」不能用「国内/海外」二分法简单归类，
 * 必须逐项能力声明。</b>这也是能力矩阵存在的根本理由。
 */
public class AntomAdapter extends AbstractChannelAdapter
        implements ChannelCapturePort, ChannelVoidPort, ChannelClosePort,
        ChannelRefundPort, ChannelRefundQueryPort {

    @Override public ChannelCode channel() { return ChannelCode.ANTOM; }

    @Override protected String pendingRawStatus() { return "PROCESSING"; }
    @Override protected String successRawStatus() { return "SUCCESS"; }
    @Override protected String failureRawStatus() { return "FAIL"; }
    @Override protected String authorizedRawStatus() { return "AUTHORIZED"; }

    @Override protected String codeUrlOf(String orderNo) { return "https://antom.com/qr/" + orderNo; }
    @Override protected String checkoutUrlOf(String orderNo) { return "https://checkout.antom.com/pay?paymentId=" + orderNo; }
    @Override protected Map<String, String> sdkParamsOf(ChannelRequest request) {
        return Map.of(
                "paymentSessionData", "antom_session_" + request.channelOrderNo(),
                "paymentId", request.channelOrderNo());
    }

    @Override
    public ChannelResult capture(ChannelCaptureRequest request) {
        Instant now = ClockHolder.now();
        int bucket = Math.abs(request.captureIdempotencyKey().hashCode()) % 100;
        if (bucket < 82) {
            return ChannelResult.succeeded(channel(), request.attemptId(), request.captureIdempotencyKey(),
                    "AN" + Math.abs(request.captureIdempotencyKey().hashCode()) % 900000000000L,
                    request.channelOrderNo(),
                    rawStatus("SUCCESS", PaymentStatus.SUCCEEDED, "请款成功"),
                    request.amount(), now, now);
        }
        return ChannelResult.failed(channel(), request.attemptId(), request.captureIdempotencyKey(),
                request.channelOrderNo(), rawStatus("FAIL", PaymentStatus.FAILED, "请款失败"),
                FailureInfo.business("ANTOM_CAPTURE_FAILED", "请款失败"), now);
    }

    @Override
    public ChannelResult voidAuthorization(ChannelVoidRequest request) {
        Instant now = ClockHolder.now();
        return ChannelResult.succeeded(channel(), request.attemptId(), request.voidIdempotencyKey(),
                null, request.channelAuthorizationId(),
                rawStatus("CLOSED", PaymentStatus.CLOSED, "授权已撤销"), null, now, now);
    }

    @Override
    public ChannelResult close(ChannelCloseRequest request) {
        Instant now = ClockHolder.now();
        return ChannelResult.succeeded(channel(), request.attemptId(), request.closeIdempotencyKey(),
                null, request.merchantOrderNo(),
                rawStatus("CLOSED", PaymentStatus.CLOSED, "订单已关闭"), null, now, now);
    }

    @Override
    public ChannelRefundResult refund(ChannelRefundRequest request) {
        Instant now = ClockHolder.now();
        String refundId = "ANRF" + Math.abs(request.refundIdempotencyKey().hashCode()) % 900000000000L;
        int bucket = Math.abs(request.refundIdempotencyKey().hashCode()) % 100;
        if (bucket < 65) {
            return ChannelRefundResult.processing(channel(), request.refundIdempotencyKey(), refundId, "PROCESSING", now);
        }
        if (bucket < 88) {
            return ChannelRefundResult.succeeded(channel(), request.refundIdempotencyKey(), refundId,
                    "SUCCESS", request.refundAmount(), now, now);
        }
        return ChannelRefundResult.failed(channel(), request.refundIdempotencyKey(), refundId, "FAIL",
                FailureInfo.business("ANTOM_REFUND_FAILED", "退款失败"), now);
    }

    @Override
    public ChannelRefundResult queryRefund(ChannelRefundQueryRequest request) {
        Instant now = ClockHolder.now();
        String refundId = request.channelRefundId() != null
                ? request.channelRefundId()
                : "ANRF" + Math.abs(request.refundIdempotencyKey().hashCode()) % 900000000000L;
        int bucket = Math.abs(request.refundIdempotencyKey().hashCode()) % 100;
        if (bucket < 82) {
            return ChannelRefundResult.succeeded(channel(), request.refundIdempotencyKey(), refundId,
                    "SUCCESS", null, now, now);
        }
        return ChannelRefundResult.processing(channel(), request.refundIdempotencyKey(), refundId, "PROCESSING", now);
    }
}
