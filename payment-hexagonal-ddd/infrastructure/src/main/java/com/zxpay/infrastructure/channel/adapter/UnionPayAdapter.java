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
 * 银联适配器。
 *
 * <p><b>注意：本适配器实际上不会被路由选中。</b>
 *
 * <p>银联的角色是<b>卡组织</b>（{@code ChannelCategory.SCHEME}），
 * 不是收单机构，因此 {@code ChannelCapability.isAcquirable()} 返回 false，
 * 能力矩阵会在路由阶段把它排除掉。
 *
 * <p>它存在的意义是演示两件事：
 * <ol>
 *   <li>配置里可以有「存在但不可用」的通道。真实系统中，
 *       商户要接银联卡必须走某家收单机构（银行或 Worldpay 这类），
 *       不能直接对接卡组织。</li>
 *   <li>银联虽然是国产体系，交易模型却与海外卡组织一致——
 *       <b>支持授权与请款分离（AUTH_ONLY / CAPTURE / VOID）</b>。
 *       这提醒我们：<b>「国内 vs 海外」不等于「SALE vs 两段式」</b>，
 *       真正的分界线是「一体化第三方支付 vs 卡组织体系」。</li>
 * </ol>
 *
 * <p>银联状态用数字编码：00 成功 / 01 失败 / 02 处理中 / 03 已关闭。
 * 这类数字状态码在对账文件里很常见，映射时必须保留原始值。
 */
public class UnionPayAdapter extends AbstractChannelAdapter
        implements ChannelCapturePort, ChannelVoidPort, ChannelRefundPort, ChannelRefundQueryPort {

    @Override public ChannelCode channel() { return ChannelCode.UNIONPAY; }

    @Override protected String pendingRawStatus() { return "02"; }
    @Override protected String successRawStatus() { return "00"; }
    @Override protected String failureRawStatus() { return "01"; }
    @Override protected String authorizedRawStatus() { return "AUTHORIZED"; }

    @Override protected String codeUrlOf(String orderNo) { return "https://qr.95516.com/" + orderNo; }
    @Override protected String checkoutUrlOf(String orderNo) { return "https://gateway.95516.com/gateway/api/frontTransReq.do?orderId=" + orderNo; }
    @Override protected Map<String, String> sdkParamsOf(ChannelRequest request) {
        // 云闪付返回 tn（交易流水号），前端调云闪付 SDK 唤起
        return Map.of("tn", request.channelOrderNo(), "scheme", "uppay");
    }

    @Override
    public ChannelResult capture(ChannelCaptureRequest request) {
        Instant now = ClockHolder.now();
        int bucket = Math.abs(request.captureIdempotencyKey().hashCode()) % 100;
        if (bucket < 78) {
            return ChannelResult.succeeded(channel(), request.attemptId(), request.captureIdempotencyKey(),
                    "UP" + Math.abs(request.captureIdempotencyKey().hashCode()) % 900000000000L,
                    request.channelOrderNo(),
                    rawStatus("00", PaymentStatus.SUCCEEDED, "请款成功"),
                    request.amount(), now, now);
        }
        return ChannelResult.failed(channel(), request.attemptId(), request.captureIdempotencyKey(),
                request.channelOrderNo(), rawStatus("01", PaymentStatus.FAILED, "请款失败"),
                FailureInfo.business("UNIONPAY_CAPTURE_FAILED", "请款失败"), now);
    }

    @Override
    public ChannelResult voidAuthorization(ChannelVoidRequest request) {
        Instant now = ClockHolder.now();
        return ChannelResult.succeeded(channel(), request.attemptId(), request.voidIdempotencyKey(),
                null, request.channelAuthorizationId(),
                rawStatus("03", PaymentStatus.CLOSED, "授权已撤销"), null, now, now);
    }

    @Override
    public ChannelRefundResult refund(ChannelRefundRequest request) {
        Instant now = ClockHolder.now();
        String refundId = "UPRF" + Math.abs(request.refundIdempotencyKey().hashCode()) % 900000000000L;
        int bucket = Math.abs(request.refundIdempotencyKey().hashCode()) % 100;
        if (bucket < 50) {
            // 卡组织退款异步，需等清算周期
            return ChannelRefundResult.processing(channel(), request.refundIdempotencyKey(), refundId, "02", now);
        }
        return ChannelRefundResult.succeeded(channel(), request.refundIdempotencyKey(), refundId, "00",
                request.refundAmount(), now, now);
    }

    @Override
    public ChannelRefundResult queryRefund(ChannelRefundQueryRequest request) {
        Instant now = ClockHolder.now();
        String refundId = request.channelRefundId() != null
                ? request.channelRefundId()
                : "UPRF" + Math.abs(request.refundIdempotencyKey().hashCode()) % 900000000000L;
        return ChannelRefundResult.succeeded(channel(), request.refundIdempotencyKey(), refundId, "00", null, now, now);
    }

    @Override
    protected ChannelRawStatus rawStatus(String raw, PaymentStatus normalized, String description) {
        return ChannelRawStatus.of(raw, normalized, description, ClockHolder.now());
    }
}
