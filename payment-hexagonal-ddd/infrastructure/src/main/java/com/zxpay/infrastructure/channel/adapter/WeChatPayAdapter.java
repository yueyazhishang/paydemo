package com.zxpay.infrastructure.channel.adapter;

import com.zxpay.domain.channel.model.ChannelCode;
import com.zxpay.domain.payment.model.ChannelRawStatus;
import com.zxpay.domain.payment.model.ChannelRequest;
import com.zxpay.domain.payment.model.ChannelResult;
import com.zxpay.domain.payment.model.FailureInfo;
import com.zxpay.domain.payment.model.PaymentStatus;
import com.zxpay.domain.payment.port.ChannelClosePort;
import com.zxpay.domain.payment.port.ChannelQueryPort;
import com.zxpay.domain.payment.port.ChannelReversePort;
import com.zxpay.domain.payment.service.IdempotencyKeyFactory;
import com.zxpay.domain.refund.model.ChannelRefundResult;
import com.zxpay.domain.refund.port.ChannelRefundPort;
import com.zxpay.domain.refund.port.ChannelRefundQueryPort;
import com.zxpay.sharedkernel.time.ClockHolder;

import java.time.Instant;
import java.util.Map;

/**
 * 微信支付适配器。
 *
 * <p>实现的端口：下单、查单、退款、退款查询、撤销、关单。
 * <b>不实现请款（Capture）与撤销授权（Void）</b>——微信是即时交易模型（SALE），
 * 下单即扣款，没有「授权」这个中间态，因此这两个能力在微信上不存在。
 *
 * <p>这正是端口隔离的价值：微信的代码里根本看不到 capture/void 方法，
 * 调用方若要对微信发起请款，连编译都过不了，
 * 而不是运行到一半抛 {@code UnsupportedOperationException}。
 *
 * <p>微信特有状态语义：
 * <ul>
 *   <li>{@code NOTPAY}：未支付，等待用户扫码/唤起。</li>
 *   <li>{@code USERPAYING}：付款码支付时用户正在输入密码。
 *       <b>此状态必须主动轮询查单</b>，不能干等通知。</li>
 *   <li>{@code PAYERROR}：支付失败，含被判盗刷的情况。</li>
 *   <li>{@code REVOKED}：已撤销（撤销接口调用成功后）。</li>
 * </ul>
 */
public class WeChatPayAdapter extends AbstractChannelAdapter
        implements ChannelRefundPort, ChannelRefundQueryPort, ChannelReversePort, ChannelClosePort {

    @Override
    public ChannelCode channel() {
        return ChannelCode.WECHAT_PAY;
    }

    @Override protected String pendingRawStatus() { return "NOTPAY"; }
    @Override protected String successRawStatus() { return "SUCCESS"; }
    @Override protected String failureRawStatus() { return "PAYERROR"; }
    @Override protected String userPayingRawStatus() { return "USERPAYING"; }
    // 微信不支持授权分离，因此这里返回 null：模拟永远不会产出 AUTHORIZED 结果
    @Override protected String authorizedRawStatus() { return null; }

    // ---------- 唤起参数 ----------

    @Override
    protected String codeUrlOf(String channelOrderNo) {
        // 微信 NATIVE 支付返回的 code_url，前端渲染成二维码
        return "weixin://wxpay/bizpayurl?pr=" + channelOrderNo;
    }

    @Override
    protected String checkoutUrlOf(String channelOrderNo) {
        // 微信 H5 支付返回的 mweb_url，需在微信外浏览器打开
        return "https://wx.tenpay.com/cgi-bin/mmpayweb-bin/checkmweb?prepay_id=" + channelOrderNo;
    }

    @Override
    protected Map<String, String> sdkParamsOf(ChannelRequest request) {
        // JSAPI / APP 支付返回唤起参数，前端调 wx.chooseWXPay 或微信 SDK
        return Map.of(
                "appId", "wx" + Math.abs(request.channelOrderNo().hashCode()) % 900000000000000L,
                "timeStamp", String.valueOf(ClockHolder.currentTimeMillis() / 1000),
                "nonceStr", request.attemptId().value().substring(0, 16),
                "package", "prepay_id=wx" + request.channelOrderNo(),
                "signType", "RSA",
                "paySign", "SIMULATED_SIGN");
    }

    // ---------- 退款 ----------

    /**
     * 微信退款。
     *
     * <p>真实实现的两个硬约束：
     * <ol>
     *   <li><b>必须带商户证书</b>。退款接口要求双向证书认证，
     *       且敏感字段（如退款通知里的用户姓名）需用 APIv3 密钥做 AEAD 解密。</li>
     *   <li><b>幂等键就是 out_refund_no</b>，直接取领域层传入的
     *       {@code refundIdempotencyKey}，不可自行生成。</li>
     * </ol>
     */
    @Override
    public ChannelRefundResult refund(ChannelRefundRequest request) {
        Instant now = ClockHolder.now();
        String refundId = "RF" + Math.abs(request.refundIdempotencyKey().hashCode()) % 900000000000L;

        int bucket = Math.abs(request.refundIdempotencyKey().hashCode()) % 100;
        if (bucket < 80) {
            // 微信退款通常同步返回结果，且即时到账
            return ChannelRefundResult.succeeded(channel(), request.refundIdempotencyKey(), refundId,
                    "SUCCESS", request.refundAmount(), now, now);
        }
        return ChannelRefundResult.failed(channel(), request.refundIdempotencyKey(), refundId, "ABNORMAL",
                FailureInfo.business("WECHAT_REFUND_FAILED", "退款失败：账户余额不足"), now);
    }

    @Override
    public ChannelRefundResult queryRefund(ChannelRefundQueryRequest request) {
        Instant now = ClockHolder.now();
        String refundId = request.channelRefundId() != null
                ? request.channelRefundId()
                : "RF" + Math.abs(request.refundIdempotencyKey().hashCode()) % 900000000000L;
        int bucket = Math.abs(request.refundIdempotencyKey().hashCode()) % 100;
        if (bucket < 85) {
            return ChannelRefundResult.succeeded(channel(), request.refundIdempotencyKey(), refundId,
                    "SUCCESS", null, now, now);
        }
        return ChannelRefundResult.processing(channel(), request.refundIdempotencyKey(), refundId,
                "PROCESSING", now);
    }

    // ---------- 撤销（国内特色，海外无对应物） ----------

    /**
     * 撤销交易。
     *
     * <p>微信的「撤销」与「退款」是两回事：
     * <ul>
     *   <li>撤销：针对<b>当天</b>交易。调用后若已支付则原路退回（免手续费、即时），
     *       若未支付则直接关闭订单。是「支付结果不确定」时的安全收尾动作。</li>
     *   <li>退款：针对已确定成功的交易，走退款流程。</li>
     * </ul>
     *
     * <p>海外卡体系没有这个能力：未请款用 VOID，已请款只能 REFUND。
     * 因此 {@code Capability.REVERSE} 是国内通道独有的能力位。
     */
    @Override
    public ChannelResult reverse(ChannelReverseRequest request) {
        Instant now = ClockHolder.now();
        return ChannelResult.succeeded(channel(), request.attemptId(), request.reverseIdempotencyKey(),
                "REV" + Math.abs(request.attemptId().value().hashCode()) % 900000000L,
                request.merchantOrderNo(),
                rawStatus("REVOKED", PaymentStatus.CLOSED, "交易已撤销，款项原路退回"),
                null, now, now);
    }

    // ---------- 关单 ----------

    @Override
    public ChannelResult close(ChannelCloseRequest request) {
        Instant now = ClockHolder.now();
        return ChannelResult.succeeded(channel(), request.attemptId(), request.closeIdempotencyKey(),
                null, request.merchantOrderNo(),
                rawStatus("CLOSED", PaymentStatus.CLOSED, "订单已关闭，不可再支付"),
                null, now, now);
    }

    @Override
    protected ChannelRawStatus rawStatus(String raw, PaymentStatus normalized, String description) {
        return ChannelRawStatus.of(raw, normalized, description, ClockHolder.now());
    }
}
