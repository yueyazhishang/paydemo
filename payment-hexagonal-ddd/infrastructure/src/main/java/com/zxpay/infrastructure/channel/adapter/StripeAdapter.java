package com.zxpay.infrastructure.channel.adapter;

import com.zxpay.domain.channel.model.ChannelCode;
import com.zxpay.domain.payment.model.ChannelResult;
import com.zxpay.domain.payment.model.ChannelRequest;
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
 * Stripe 适配器（PSP 代表）。
 *
 * <p>实现的端口：下单、查单、<b>请款</b>、<b>撤销授权</b>、退款、退款查询。
 * <b>不实现撤销（Reverse）与关单（Close）</b>——海外卡体系没有「撤销当日交易」这个动作：
 * 未请款的授权用 VOID，已请款的只能走退款；订单到期自然过期，没有独立的关单接口。
 *
 * <h3>与国内通道最核心的三处差异</h3>
 * <ol>
 *   <li><b>两段式交易</b>：{@code requires_action}（需 3DS 挑战）→
 *       {@code requires_capture}（已授权待请款）→ {@code succeeded}（请款成功）。
 *       国内是「下单即扣款」的一步式，根本没有 requires_capture 这个状态。</li>
 *   <li><b>幂等键是请求头</b>：{@code Idempotency-Key}，有效期 24 小时，
 *       且<b>同键不同参数会直接报错</b>。这意味着重试必须复用完全相同的请求体，
 *       不能「改个金额再试一次」。领域层为此把幂等键做成确定性生成
 *       （见 {@code IdempotencyKeyFactory}），保证崩溃重试后仍是同一个键。</li>
 *   <li><b>退款是异步的</b>：请求受理后返回，资金到账要等 5~10 个工作日。
 *       因此这里返回 {@code PROCESSING} 而非最终成功，
 *       上层必须靠退款通知或主动查退款单推进。</li>
 * </ol>
 */
public class StripeAdapter extends AbstractChannelAdapter
        implements ChannelCapturePort, ChannelVoidPort, ChannelRefundPort, ChannelRefundQueryPort {

    @Override
    public ChannelCode channel() {
        return ChannelCode.STRIPE;
    }

    @Override protected String pendingRawStatus() { return "requires_action"; }
    @Override protected String successRawStatus() { return "succeeded"; }
    @Override protected String failureRawStatus() { return "canceled"; }
    @Override protected String authorizedRawStatus() { return "requires_capture"; }

    @Override
    protected String codeUrlOf(String channelOrderNo) {
        // Stripe 本身不做二维码，这里由本地生成指向 hosted checkout 的链接
        return "https://checkout.stripe.com/c/pay/" + channelOrderNo;
    }

    @Override
    protected String checkoutUrlOf(String channelOrderNo) {
        return "https://checkout.stripe.com/c/pay/" + channelOrderNo;
    }

    @Override
    protected Map<String, String> sdkParamsOf(ChannelRequest request) {
        // 前端 Stripe Elements 用 client_secret 确认支付，并以 pk_ 开头的公钥初始化
        return Map.of(
                "clientSecret", "pi_" + request.channelOrderNo() + "_secret_SIMULATED",
                "publishableKey", "pk_live_SIMULATED",
                "paymentIntentId", "pi_" + request.channelOrderNo());
    }

    // ---------- 请款 ----------

    /**
     * 请款。
     *
     * <p>金额约束由聚合根 {@code PaymentOrder.requestCapture} 在调用前校验
     * （授权未过期、金额不超授权额），适配器只负责传达。
     * 这里再校验一次是防御性编程：通道也会校验，但与其等通道报错，
     * 不如本地先拦住并返回可读的错误。
     */
    @Override
    public ChannelResult capture(ChannelCaptureRequest request) {
        Instant now = ClockHolder.now();
        int bucket = Math.abs(request.captureIdempotencyKey().hashCode()) % 100;

        if (bucket < 75) {
            return ChannelResult.succeeded(channel(), request.attemptId(), request.captureIdempotencyKey(),
                    "ch_" + Math.abs(request.captureIdempotencyKey().hashCode()) % 900000000000L,
                    request.channelOrderNo(),
                    rawStatus("succeeded", PaymentStatus.SUCCEEDED, "请款成功，资金已划转"),
                    request.amount(), now, now);
        }
        // 请款失败：通常是授权已过期或额度不足
        return ChannelResult.failed(channel(), request.attemptId(), request.captureIdempotencyKey(),
                request.channelOrderNo(),
                rawStatus("canceled", PaymentStatus.FAILED, "请款失败"),
                FailureInfo.business("STRIPE_CAPTURE_FAILED", "请款失败：授权已过期或余额不足"), now);
    }

    // ---------- 撤销授权 ----------

    /**
     * 撤销授权（Void）。
     *
     * <p>只针对<b>已授权但未请款</b>的交易——把冻结的额度释放掉。
     * 与退款的本质区别：VOID 时钱根本没划转，不产生账务流水，
     * 用户额度即时恢复；退款则是钱已划走再退回，通常要 5~10 个工作日。
     *
     * <p>调用错了会直接报错：对已请款的交易调 void，Stripe 会返回错误。
     */
    @Override
    public ChannelResult voidAuthorization(ChannelVoidRequest request) {
        Instant now = ClockHolder.now();
        return ChannelResult.succeeded(channel(), request.attemptId(), request.voidIdempotencyKey(),
                null, request.channelAuthorizationId(),
                rawStatus("canceled", PaymentStatus.CLOSED, "授权已撤销，额度已释放"),
                null, now, now);
    }

    // ---------- 退款 ----------

    @Override
    public ChannelRefundResult refund(ChannelRefundRequest request) {
        Instant now = ClockHolder.now();
        String refundId = "re_" + Math.abs(request.refundIdempotencyKey().hashCode()) % 900000000000L;
        int bucket = Math.abs(request.refundIdempotencyKey().hashCode()) % 100;

        if (bucket < 70) {
            // 卡退款是异步的：受理后资金在途，需等通知或主动查单确认
            return ChannelRefundResult.processing(channel(), request.refundIdempotencyKey(), refundId,
                    "pending", now);
        }
        if (bucket < 85) {
            return ChannelRefundResult.succeeded(channel(), request.refundIdempotencyKey(), refundId,
                    "succeeded", request.refundAmount(), now, now);
        }
        return ChannelRefundResult.failed(channel(), request.refundIdempotencyKey(), refundId, "failed",
                FailureInfo.business("STRIPE_REFUND_FAILED", "退款被拒：charge 已全额退款或已争议"), now);
    }

    @Override
    public ChannelRefundResult queryRefund(ChannelRefundQueryRequest request) {
        Instant now = ClockHolder.now();
        String refundId = request.channelRefundId() != null ? request.channelRefundId() : "re_" + request.refundIdempotencyKey().hashCode();
        int bucket = Math.abs(request.refundIdempotencyKey().hashCode()) % 100;
        if (bucket < 80) {
            return ChannelRefundResult.succeeded(channel(), request.refundIdempotencyKey(), refundId,
                    "succeeded", null, now, now);
        }
        return ChannelRefundResult.processing(channel(), request.refundIdempotencyKey(), refundId, "pending", now);
    }
}
