package com.zxpay.infrastructure.channel.adapter;

import com.zxpay.domain.channel.model.ChannelCode;
import com.zxpay.domain.payment.model.ChannelRequest;
import com.zxpay.domain.payment.model.ChannelResult;
import com.zxpay.domain.payment.model.FailureInfo;
import com.zxpay.domain.payment.model.PaymentStatus;
import com.zxpay.domain.payment.port.ChannelClosePort;
import com.zxpay.domain.payment.port.ChannelReversePort;
import com.zxpay.domain.refund.model.ChannelRefundResult;
import com.zxpay.domain.refund.port.ChannelRefundPort;
import com.zxpay.domain.refund.port.ChannelRefundQueryPort;
import com.zxpay.sharedkernel.time.ClockHolder;

import java.time.Instant;
import java.util.Map;

/**
 * 支付宝适配器。
 *
 * <p>与微信同属国内一体化第三方支付，实现端口也基本一致。
 * 真正的差异在两处：
 *
 * <ol>
 *   <li><b>签名模型</b>：微信 APIv3 用商户证书，支付宝用应用私钥做 RSA2 签名。
 *       这看似是实现细节，却影响运维——证书有有效期需要轮换，
 *       而 RSA 密钥对一旦泄露要整体更换。见 {@code AuthModel.RSA2_KEY_PAIR}。</li>
 *
 *   <li><b>TRADE_SUCCESS 与 TRADE_FINISHED 的区别</b>：
 *       两者归一化后都是 {@code SUCCEEDED}，但语义不同——
 *       {@code TRADE_SUCCESS} 表示可退款，{@code TRADE_FINISHED} 表示
 *       <b>不可退款</b>（超出退款期限或已全额退款完成）。
 *       <b>如果只看归一化状态就发起退款，会对一笔注定失败的请求白跑一趟。</b>
 *       这就是为什么 {@code ChannelRawStatus} 必须与归一化状态双轨保留。</li>
 * </ol>
 *
 * <p>支付宝没有 {@code USERPAYING} 这个中间态：
 * 条码支付超时后直接返回失败，由商户决定是否重试或撤销。
 * 因此本适配器 {@code userPayingRawStatus()} 返回 null。
 */
public class AlipayAdapter extends AbstractChannelAdapter
        implements ChannelRefundPort, ChannelRefundQueryPort, ChannelReversePort, ChannelClosePort {

    @Override
    public ChannelCode channel() {
        return ChannelCode.ALIPAY;
    }

    @Override protected String pendingRawStatus() { return "WAIT_BUYER_PAY"; }
    @Override protected String successRawStatus() { return "TRADE_SUCCESS"; }
    @Override protected String failureRawStatus() { return "TRADE_CLOSED"; }

    @Override
    protected String codeUrlOf(String channelOrderNo) {
        return "https://qr.alipay.com/" + channelOrderNo;
    }

    @Override
    protected String checkoutUrlOf(String channelOrderNo) {
        return "https://openapi.alipay.com/gateway.do?out_trade_no=" + channelOrderNo;
    }

    @Override
    protected Map<String, String> sdkParamsOf(ChannelRequest request) {
        // 支付宝 APP 支付返回 orderString，前端直接传给 SDK 唤起
        return Map.of("orderString", "alipay_sdk=alipay-sdk&out_trade_no=" + request.channelOrderNo()
                + "&sign=SIMULATED");
    }

    @Override
    public ChannelRefundResult refund(ChannelRefundRequest request) {
        Instant now = ClockHolder.now();
        String refundId = "ALRF" + Math.abs(request.refundIdempotencyKey().hashCode()) % 900000000000L;
        int bucket = Math.abs(request.refundIdempotencyKey().hashCode()) % 100;
        if (bucket < 82) {
            // 支付宝退款同步返回结果
            return ChannelRefundResult.succeeded(channel(), request.refundIdempotencyKey(), refundId,
                    "REFUND_SUCCESS", request.refundAmount(), now, now);
        }
        return ChannelRefundResult.failed(channel(), request.refundIdempotencyKey(), refundId, "REFUND_FAILED",
                FailureInfo.business("ALIPAY_REFUND_FAILED", "退款失败"),
                now);
    }

    @Override
    public ChannelRefundResult queryRefund(ChannelRefundQueryRequest request) {
        Instant now = ClockHolder.now();
        String refundId = request.channelRefundId() != null ? request.channelRefundId() : "ALRF" + request.refundIdempotencyKey().hashCode();
        return ChannelRefundResult.succeeded(channel(), request.refundIdempotencyKey(), refundId,
                "REFUND_SUCCESS", null, now, now);
    }

    @Override
    public ChannelResult reverse(ChannelReverseRequest request) {
        Instant now = ClockHolder.now();
        return ChannelResult.succeeded(channel(), request.attemptId(), request.reverseIdempotencyKey(),
                "ALRV" + Math.abs(request.attemptId().value().hashCode()) % 900000000L,
                request.merchantOrderNo(),
                rawStatus("TRADE_CLOSED", PaymentStatus.CLOSED, "交易已撤销"),
                null, now, now);
    }

    @Override
    public ChannelResult close(ChannelCloseRequest request) {
        Instant now = ClockHolder.now();
        return ChannelResult.succeeded(channel(), request.attemptId(), request.closeIdempotencyKey(),
                null, request.merchantOrderNo(),
                rawStatus("TRADE_CLOSED", PaymentStatus.CLOSED, "订单已关闭"),
                null, now, now);
    }
}
