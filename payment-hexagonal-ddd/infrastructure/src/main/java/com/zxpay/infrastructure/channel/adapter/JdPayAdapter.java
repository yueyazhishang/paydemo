package com.zxpay.infrastructure.channel.adapter;

import com.zxpay.domain.channel.model.ChannelCode;
import com.zxpay.domain.payment.model.ChannelRequest;
import com.zxpay.domain.payment.model.ChannelResult;
import com.zxpay.domain.payment.model.FailureInfo;
import com.zxpay.domain.payment.model.PaymentStatus;
import com.zxpay.domain.payment.port.ChannelClosePort;
import com.zxpay.domain.refund.model.ChannelRefundResult;
import com.zxpay.domain.refund.port.ChannelRefundPort;
import com.zxpay.domain.refund.port.ChannelRefundQueryPort;
import com.zxpay.sharedkernel.time.ClockHolder;

import java.time.Instant;
import java.util.Map;

/**
 * 京东支付适配器。
 *
 * <p>能力集明显小于微信/支付宝：没有撤销（REVERSE）、没有担保交易、没有分账，
 * 部分退款次数上限也只有 10 次（微信 50 次、支付宝不限）。
 *
 * <p>这个「能力不全」的通道在架构上很有价值：它逼着我们把能力差异建模成数据。
 * 若用 if-else 写法，这些差异会散落在各个业务方法里，接一家通道要改十几处；
 * 能力矩阵方案下，接它只是加一份配置 + 一个适配器。
 */
public class JdPayAdapter extends AbstractChannelAdapter
        implements ChannelRefundPort, ChannelRefundQueryPort, ChannelClosePort {

    @Override public ChannelCode channel() { return ChannelCode.JD_PAY; }

    @Override protected String pendingRawStatus() { return "WAIT"; }
    @Override protected String successRawStatus() { return "SUCCESS"; }
    @Override protected String failureRawStatus() { return "FAILED"; }

    @Override protected String codeUrlOf(String orderNo) { return "https://pay.jd.com/qr/" + orderNo; }
    @Override protected String checkoutUrlOf(String orderNo) { return "https://pay.jd.com/h5?orderNo=" + orderNo; }
    @Override protected Map<String, String> sdkParamsOf(ChannelRequest request) {
        return Map.of("payData", "jd_sdk&tradeNo=" + request.channelOrderNo() + "&sign=SIMULATED");
    }

    @Override
    public ChannelRefundResult refund(ChannelRefundRequest request) {
        Instant now = ClockHolder.now();
        String refundId = "JDRF" + Math.abs(request.refundIdempotencyKey().hashCode()) % 900000000000L;
        int bucket = Math.abs(request.refundIdempotencyKey().hashCode()) % 100;
        if (bucket < 78) {
            return ChannelRefundResult.succeeded(channel(), request.refundIdempotencyKey(), refundId,
                    "REFUND_SUCCESS", request.refundAmount(), now, now);
        }
        return ChannelRefundResult.failed(channel(), request.refundIdempotencyKey(), refundId, "REFUND_FAILED",
                FailureInfo.business("JD_REFUND_FAILED", "退款失败"), now);
    }

    @Override
    public ChannelRefundResult queryRefund(ChannelRefundQueryRequest request) {
        Instant now = ClockHolder.now();
        String refundId = request.channelRefundId() != null
                ? request.channelRefundId()
                : "JDRF" + Math.abs(request.refundIdempotencyKey().hashCode()) % 900000000000L;
        return ChannelRefundResult.succeeded(channel(), request.refundIdempotencyKey(), refundId,
                "REFUND_SUCCESS", null, now, now);
    }

    @Override
    public ChannelResult close(ChannelCloseRequest request) {
        Instant now = ClockHolder.now();
        return ChannelResult.succeeded(channel(), request.attemptId(), request.closeIdempotencyKey(),
                null, request.merchantOrderNo(),
                rawStatus("CLOSED", PaymentStatus.CLOSED, "订单已关闭"), null, now, now);
    }
}
