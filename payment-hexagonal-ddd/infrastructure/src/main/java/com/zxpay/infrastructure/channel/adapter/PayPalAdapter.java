package com.zxpay.infrastructure.channel.adapter;

import com.zxpay.domain.channel.model.ChannelCode;
import com.zxpay.domain.payment.model.ChannelRequest;
import com.zxpay.domain.payment.model.ChannelResult;
import com.zxpay.domain.payment.model.FailureInfo;
import com.zxpay.domain.payment.model.PaymentStatus;
import com.zxpay.domain.payment.port.ChannelCapturePort;
import com.zxpay.domain.payment.port.ChannelVoidPort;
import com.zxpay.domain.refund.model.ChannelRefundResult;
import com.zxpay.domain.refund.port.ChannelRefundPort;
import com.zxpay.domain.refund.port.ChannelRefundQueryPort;
import com.zxpay.sharedkernel.time.ClockHolder;

import java.time.Instant;
import java.util.Map;

/**
 * PayPal 适配器。
 *
 * <p>兼具钱包与 PSP 属性：既有用户余额账户（PAYPAL_WALLET），
 * 也处理卡收单（CARD，底层是 Braintree）。
 *
 * <p><b>与国内通道最实质的差异：{@code originalMethodOnly = false}</b>。
 * 退款可以退到用户的 PayPal 账户余额，而不必原路退回银行卡。
 * 国内通道基本都强制原路退回——这个差异直接影响用户体感
 * （退到余额是即时可用的，退到卡要等好几天）。
 *
 * <p>另两处值得注意：
 * <ul>
 *   <li>{@code APPROVED} 表示用户已授权但资金尚未划转，需商户调请款接口完成。
 *       这一步国内没有——用户点了付款钱就扣了。</li>
 *   <li>退款窗口 180 天，与争议期一致。超期后无法发起退款，
 *       只能走线下转账，财务流程完全不同。</li>
 * </ul>
 */
public class PayPalAdapter extends AbstractChannelAdapter
        implements ChannelCapturePort, ChannelVoidPort, ChannelRefundPort, ChannelRefundQueryPort {

    @Override public ChannelCode channel() { return ChannelCode.PAYPAL; }

    @Override protected String pendingRawStatus() { return "CREATED"; }
    @Override protected String successRawStatus() { return "COMPLETED"; }
    @Override protected String failureRawStatus() { return "VOIDED"; }
    @Override protected String authorizedRawStatus() { return "APPROVED"; }

    @Override protected String codeUrlOf(String orderNo) { return "https://www.paypal.com/qr/" + orderNo; }
    @Override protected String checkoutUrlOf(String orderNo) { return "https://www.paypal.com/checkoutnow?token=" + orderNo; }
    @Override protected Map<String, String> sdkParamsOf(ChannelRequest request) {
        return Map.of("orderId", "EC-" + request.channelOrderNo(), "approveUrl", checkoutUrlOf(request.channelOrderNo()));
    }

    @Override
    public ChannelResult capture(ChannelCaptureRequest request) {
        Instant now = ClockHolder.now();
        int bucket = Math.abs(request.captureIdempotencyKey().hashCode()) % 100;
        if (bucket < 80) {
            return ChannelResult.succeeded(channel(), request.attemptId(), request.captureIdempotencyKey(),
                    "CAP-" + Math.abs(request.captureIdempotencyKey().hashCode()) % 900000000000L,
                    request.channelOrderNo(),
                    rawStatus("COMPLETED", PaymentStatus.SUCCEEDED, "请款成功"),
                    request.amount(), now, now);
        }
        return ChannelResult.failed(channel(), request.attemptId(), request.captureIdempotencyKey(),
                request.channelOrderNo(), rawStatus("VOIDED", PaymentStatus.FAILED, "请款失败"),
                FailureInfo.business("PAYPAL_CAPTURE_FAILED", "请款失败：授权已过期"), now);
    }

    @Override
    public ChannelResult voidAuthorization(ChannelVoidRequest request) {
        Instant now = ClockHolder.now();
        return ChannelResult.succeeded(channel(), request.attemptId(), request.voidIdempotencyKey(),
                null, request.channelAuthorizationId(),
                rawStatus("VOIDED", PaymentStatus.CLOSED, "授权已作废"), null, now, now);
    }

    @Override
    public ChannelRefundResult refund(ChannelRefundRequest request) {
        Instant now = ClockHolder.now();
        String refundId = "PPRF" + Math.abs(request.refundIdempotencyKey().hashCode()) % 900000000000L;
        int bucket = Math.abs(request.refundIdempotencyKey().hashCode()) % 100;
        if (bucket < 60) {
            // 退到 PayPal 余额是即时的；退到卡则异步
            return ChannelRefundResult.succeeded(channel(), request.refundIdempotencyKey(), refundId,
                    "COMPLETED", request.refundAmount(), now, now);
        }
        if (bucket < 85) {
            return ChannelRefundResult.processing(channel(), request.refundIdempotencyKey(), refundId, "PENDING", now);
        }
        return ChannelRefundResult.failed(channel(), request.refundIdempotencyKey(), refundId, "FAILED",
                FailureInfo.business("PAYPAL_REFUND_EXPIRED", "超出 180 天退款窗口"), now);
    }

    @Override
    public ChannelRefundResult queryRefund(ChannelRefundQueryRequest request) {
        Instant now = ClockHolder.now();
        String refundId = request.channelRefundId() != null
                ? request.channelRefundId()
                : "PPRF" + Math.abs(request.refundIdempotencyKey().hashCode()) % 900000000000L;
        int bucket = Math.abs(request.refundIdempotencyKey().hashCode()) % 100;
        if (bucket < 85) {
            return ChannelRefundResult.succeeded(channel(), request.refundIdempotencyKey(), refundId,
                    "COMPLETED", null, now, now);
        }
        return ChannelRefundResult.processing(channel(), request.refundIdempotencyKey(), refundId, "PENDING", now);
    }
}
