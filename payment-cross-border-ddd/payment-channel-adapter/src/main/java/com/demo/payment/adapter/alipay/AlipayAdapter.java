package com.demo.payment.adapter.alipay;

import com.demo.payment.adapter.core.AbstractChannelAdapter;
import com.demo.payment.domain.acquiring.model.vo.OutTradeNo;
import com.demo.payment.domain.channel.model.*;
import com.demo.payment.domain.channel.spi.*;
import com.demo.payment.shared.money.Currency;
import com.demo.payment.shared.money.Money;

import java.time.Instant;
import java.util.Set;

/**
 * 支付宝适配器。
 *
 * <h3>与微信的关键差异</h3>
 * <ol>
 *   <li><b>支持撤销（cancel）</b>：这是支付宝区别于微信的重要能力。
 *       {@code alipay.trade.cancel} 的语义是"未支付则关闭，已支付则退款"，
 *       一个接口同时覆盖两种场景，实现时要注意区分返回的 action 字段。</li>
 *   <li><b>回调是 form-urlencoded</b>，不是 JSON。签名放在 {@code sign} 参数里，
 *       且需要<b>剔除 sign 和 sign_type 后按 key 排序</b>再验签。
 *       这个"排序 + 剔除"的细节是验签失败的高发原因。</li>
 *   <li><b>公钥证书 vs 公钥字符串</b>：支付宝同时支持两种模式，
 *       证书模式需要定期更新支付宝公钥证书（类似微信）。</li>
 *   <li><b>异步通知需要返回 "success" 字符串</b>：返回其他内容会导致支付宝
 *       不断重投（最多 8 次），这是新手常踩的坑。</li>
 * </ol>
 */
public class AlipayAdapter extends AbstractChannelAdapter {

    private static final ChannelCapability CAPABILITY = new ChannelCapability(
            ChannelCode.ALIPAY,
            "支付宝",
            ChannelCapability.AcquiringModel.WALLET,
            Set.of(PaymentMethodType.ALIPAY_WALLET, PaymentMethodType.BANK_CARD),
            false,
            true,
            true,
            true,
            365,
            false,
            ChannelCapability.NotifyMode.PUSH_AND_PULL,
            ChannelCapability.IdempotencyMode.MERCHANT_ORDER_NO_ONLY,
            ChannelCapability.SignatureAlgorithm.ALIPAY_RSA2,
            true,
            Set.of(ChannelCapability.IntegrationMode.QR_CODE, ChannelCapability.IntegrationMode.NATIVE_SDK, ChannelCapability.IntegrationMode.REDIRECT_CHECKOUT),
            Set.of(Currency.CNY),
            1L,
            100000000L,
            java.time.Duration.ofMinutes(120),
            true,
            ChannelCapability.SettlementMode.IMMEDIATE
    );

    @Override
    public ChannelCode channelCode() {
        return ChannelCode.ALIPAY;
    }

    @Override
    public ChannelCapability capability() {
        return CAPABILITY;
    }

    @Override
    protected PayResponse doPay(PayCommand command) {
        String productCode = command.extraParams().getOrDefault("productCode", "FACE_TO_FACE_PAYMENT");

        // TODO 真实实现：
        //   当面付  → alipay.trade.precreate  （返回 qr_code）
        //   手机网站 → alipay.trade.wap.pay    （返回 form 表单，自动跳转）
        //   APP     → alipay.trade.app.pay     （返回 orderString）
        //   PC      → alipay.trade.page.pay    （返回 form 表单）
        String outTradeNo = command.outTradeNo().value();

        return PayResponse.pending(command.outTradeNo(), cred(
                "productCode", productCode,
                "qrCode", "https://qr.alipay.com/mock_" + outTradeNo,
                "orderString", "alipay_sdk=mock&out_trade_no=" + outTradeNo,
                "amount", command.amount().majorValue().toPlainString()
        ));
    }

    @Override
    protected QueryResponse doQuery(QueryCommand command) {
        // TODO 真实实现：alipay.trade.query
        //   注意 trade_status 有 WAIT_BUYER_PAY / TRADE_SUCCESS / TRADE_CLOSED
        //   和 TRADE_FINISHED（已结算不可退款）—— 后者是支付宝独有的状态
        return QueryResponse.unknown(command.outTradeNo(), "Mock: 未实现真实查证");
    }

    @Override
    protected CloseResponse doClose(CloseCommand command) {
        // TODO 真实实现：alipay.trade.close（仅对 WAIT_BUYER_PAY 状态生效）
        return CloseResponse.success(command.outTradeNo());
    }

    /**
     * 撤销 —— 支付宝特有能力。
     *
     * <p>{@code alipay.trade.cancel} 会根据订单当前状态自动选择动作：
     * 未支付 → 关闭；已支付 → 发起退款。实现时必须读取返回的 {@code action} 字段
     * 才能知道实际发生了什么。
     */
    @Override
    protected CancelResponse doCancel(CancelCommand command) {
        // TODO 真实实现：alipay.trade.cancel
        return CancelResponse.success(command.outTradeNo());
    }

    @Override
    protected RefundResponse doRefund(RefundCommand command) {
        // TODO 真实实现：alipay.trade.refund
        //   注意 out_request_no 是退款单号，同一订单多次部分退款时该号必须不同
        return RefundResponse.succeeded(command.outRefundNo(), "ALIRF" + System.currentTimeMillis(),
                command.amount());
    }

    @Override
    public NotificationParseResult parseNotification(RawNotification raw) {
        // 步骤一：解析 form-urlencoded body
        // 步骤二：剔除 sign / sign_type，剩余参数按 key 升序拼接
        // 步骤三：用支付宝公钥做 SHA256withRSA 验签
        verifySign(raw.body());

        return new NotificationParseResult(
                OutTradeNo.of("MOCK"),
                "MOCK_TXN",
                ChannelResultStatus.PENDING,
                "WAIT_BUYER_PAY",
                Money.ofMinor(0L, Currency.CNY),
                "MOCK_NOTIFY_ID",
                "payment",
                Instant.now(),
                raw.body()
        );
    }

    private void verifySign(String body) {
        if (body == null || !body.contains("sign=")) {
            throw new SecurityException("支付宝回调缺少签名字段，拒绝处理");
        }
        // TODO 真实实现：排序 → 拼接 → RSA2 验签
    }


}
