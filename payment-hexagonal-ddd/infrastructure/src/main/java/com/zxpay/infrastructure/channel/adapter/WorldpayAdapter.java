package com.zxpay.infrastructure.channel.adapter;

import com.zxpay.domain.channel.model.ChannelCode;
import com.zxpay.domain.payment.model.ChannelRawStatus;
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
 * Worldpay 适配器（老牌收单机构 / 网关）。
 *
 * <p>三处必须知道的限制，都写在能力矩阵里：
 *
 * <ol>
 *   <li><b>幂等行为未定义</b>（{@code ConflictBehaviour.UNDEFINED}）。
 *       重试时通道既可能返回原结果，也可能再扣一笔。
 *       <b>这意味着不能依赖通道的幂等保护</b>，
 *       超时后必须走「主动查单」确认，而不是盲目重试。
 *       这是所有通道里最需要谨慎处理的一家。</li>
 *   <li><b>结算后不支持退款</b>（{@code supportsRefundAfterSettlement = false}）。
 *       资金已结算给商户后无法再退。
 *       因此业务上要控制结算节奏，或预留保证金账户应对退款。</li>
 *   <li><b>不支持多次部分退款</b>（无 {@code MULTIPLE_PARTIAL_REFUND}）。
 *       一笔交易只能退一次，无论金额是否为全额。
 *       想分多次退，必须在业务层自行拆单。</li>
 * </ol>
 *
 * <p>状态语义：{@code AUTHORIZED} 已授权 / {@code CAPTURED} 已请款 /
 * {@code REFUSED} 被拒 / {@code CANCELLED} 已取消 / {@code SETTLED} 已结算。
 * 注意 {@code SETTLED} 是银联、支付宝等体系里没有的概念——
 * 它表示资金已经过了清算窗口，这时候再想退款就晚了。
 */
public class WorldpayAdapter extends AbstractChannelAdapter
        implements ChannelCapturePort, ChannelVoidPort, ChannelRefundPort, ChannelRefundQueryPort {

    @Override public ChannelCode channel() { return ChannelCode.WORLDPAY; }

    @Override protected String pendingRawStatus() { return "PENDING"; }
    @Override protected String successRawStatus() { return "CAPTURED"; }
    @Override protected String failureRawStatus() { return "REFUSED"; }
    @Override protected String authorizedRawStatus() { return "AUTHORIZED"; }

    @Override protected String codeUrlOf(String orderNo) { return "https://pay.worldpay.com/qr/" + orderNo; }
    @Override protected String checkoutUrlOf(String orderNo) { return "https://pay.worldpay.com/checkout?order=" + orderNo; }
    @Override protected Map<String, String> sdkParamsOf(ChannelRequest request) {
        // Worldpay 的 Own Form / SDK 方案，前端用 session 完成卡信息采集
        return Map.of("checkoutSessionId", "wp_session_" + request.channelOrderNo());
    }

    @Override
    public ChannelResult capture(ChannelCaptureRequest request) {
        Instant now = ClockHolder.now();
        int bucket = Math.abs(request.captureIdempotencyKey().hashCode()) % 100;
        if (bucket < 80) {
            return ChannelResult.succeeded(channel(), request.attemptId(), request.captureIdempotencyKey(),
                    "WP" + Math.abs(request.captureIdempotencyKey().hashCode()) % 900000000000L,
                    request.channelOrderNo(),
                    rawStatus("CAPTURED", PaymentStatus.SUCCEEDED, "请款成功"),
                    request.amount(), now, now);
        }
        return ChannelResult.failed(channel(), request.attemptId(), request.captureIdempotencyKey(),
                request.channelOrderNo(), rawStatus("REFUSED", PaymentStatus.FAILED, "请款被拒"),
                FailureInfo.business("WORLDPAY_CAPTURE_REFUSED", "请款被拒：授权已过期"), now);
    }

    @Override
    public ChannelResult voidAuthorization(ChannelVoidRequest request) {
        Instant now = ClockHolder.now();
        return ChannelResult.succeeded(channel(), request.attemptId(), request.voidIdempotencyKey(),
                null, request.channelAuthorizationId(),
                rawStatus("CANCELLED", PaymentStatus.CLOSED, "授权已撤销"), null, now, now);
    }

    @Override
    public ChannelRefundResult refund(ChannelRefundRequest request) {
        Instant now = ClockHolder.now();
        String refundId = "WPRF" + Math.abs(request.refundIdempotencyKey().hashCode()) % 900000000000L;
        int bucket = Math.abs(request.refundIdempotencyKey().hashCode()) % 100;

        if (bucket < 15) {
            // 已结算后退款会被拒绝——这正是能力矩阵要提前拦截的情况
            return ChannelRefundResult.failed(channel(), request.refundIdempotencyKey(), refundId, "REFUSED",
                    FailureInfo.business("WORLDPAY_SETTLED_NO_REFUND", "资金已结算，不支持退款"), now);
        }
        if (bucket < 55) {
            return ChannelRefundResult.processing(channel(), request.refundIdempotencyKey(), refundId, "PENDING", now);
        }
        if (bucket < 88) {
            return ChannelRefundResult.succeeded(channel(), request.refundIdempotencyKey(), refundId,
                    "REFUNDED", request.refundAmount(), now, now);
        }
        return ChannelRefundResult.failed(channel(), request.refundIdempotencyKey(), refundId, "REFUSED",
                FailureInfo.business("WORLDPAY_REFUND_EXPIRED", "超出 180 天退款窗口"), now);
    }

    @Override
    public ChannelRefundResult queryRefund(ChannelRefundQueryRequest request) {
        Instant now = ClockHolder.now();
        String refundId = request.channelRefundId() != null
                ? request.channelRefundId()
                : "WPRF" + Math.abs(request.refundIdempotencyKey().hashCode()) % 900000000000L;
        int bucket = Math.abs(request.refundIdempotencyKey().hashCode()) % 100;
        if (bucket < 78) {
            return ChannelRefundResult.succeeded(channel(), request.refundIdempotencyKey(), refundId,
                    "REFUNDED", null, now, now);
        }
        return ChannelRefundResult.processing(channel(), request.refundIdempotencyKey(), refundId, "PENDING", now);
    }

    @Override
    protected ChannelRawStatus rawStatus(String raw, PaymentStatus normalized, String description) {
        return ChannelRawStatus.of(raw, normalized, description, ClockHolder.now());
    }
}
