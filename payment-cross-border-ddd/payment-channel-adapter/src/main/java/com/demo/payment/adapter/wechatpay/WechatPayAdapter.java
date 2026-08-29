package com.demo.payment.adapter.wechatpay;

import com.demo.payment.adapter.core.AbstractChannelAdapter;
import com.demo.payment.domain.acquiring.model.vo.OutTradeNo;
import com.demo.payment.domain.channel.model.*;
import com.demo.payment.domain.channel.spi.*;
import com.demo.payment.shared.money.Currency;
import com.demo.payment.shared.money.Money;

import java.time.Instant;
import java.util.Set;

/**
 * 微信支付 v3 适配器。
 *
 * <h3>三个必须知道的坑</h3>
 * <ol>
 *   <li><b>平台证书自动轮换</b>：微信的平台证书会定期更换，且更换<b>不提前通知</b>。
 *       硬编码证书的系统会在某一天突然全部验签失败，表现为"所有回调都失效"。
 *       正确做法：启动时 + 每 12 小时调用「获取平台证书」接口下载，
 *       按 {@code Wechatpay-Serial} 头选择对应证书验签。</li>
 *   <li><b>回调报文是加密的</b>：v3 的 {@code resource} 字段是 AES-256-GCM 密文，
 *       必须先用 APIv3 密钥解密才能拿到真实内容。步骤是：
 *       验签 → 解密 resource → 再验金额。顺序错了必然出问题。</li>
 *   <li><b>没有幂等头</b>：微信不提供 Idempotency-Key。
 *       因此<b>重试前必须先查单</b>，否则同一 out_trade_no 重复下单会返回
 *       "订单已存在"；更危险的是如果换号重试，可能造成<b>重复扣款</b>。
 *       这是国内通道与 Stripe 最大的工程差异。</li>
 * </ol>
 *
 * <h3>金额单位</h3>
 * <p>微信 v3 的 {@code amount.total} 单位是<b>分</b>，且必须是整数。
 * 若传入小数会直接报参数错误。Money 内部以最小单位存储，天然对齐。
 */
public class WechatPayAdapter extends AbstractChannelAdapter {

    private static final ChannelCapability CAPABILITY = new ChannelCapability(
            ChannelCode.WECHAT_PAY,
            "微信支付",
            ChannelCapability.AcquiringModel.WALLET,
            Set.of(PaymentMethodType.WECHAT_PAY),
            false,
            false,
            true,
            true,
            365,
            false,
            ChannelCapability.NotifyMode.PUSH_AND_PULL,
            ChannelCapability.IdempotencyMode.MERCHANT_ORDER_NO_ONLY,
            ChannelCapability.SignatureAlgorithm.WECHATPAY_RSA_SHA256,
            true,
            Set.of(ChannelCapability.IntegrationMode.NATIVE_SDK, ChannelCapability.IntegrationMode.QR_CODE),
            Set.of(Currency.CNY),
            1L,
            50000000L,
            java.time.Duration.ofMinutes(120),
            true,
            ChannelCapability.SettlementMode.IMMEDIATE
    );

    @Override
    public ChannelCode channelCode() {
        return ChannelCode.WECHAT_PAY;
    }

    @Override
    public ChannelCapability capability() {
        return CAPABILITY;
    }

    /**
     * 下单。
     *
     * <p>真实实现要点：
     * <pre>
     *   1. 按 tradeType 选择接口：
     *      JSAPI  → /v3/pay/transactions/jsapi    （必须传 payer.openid）
     *      NATIVE → /v3/pay/transactions/native    （返回 code_url，生成二维码）
     *      APP    → /v3/pay/transactions/app       （返回 prepay_id，前端唤起）
     *      H5     → /v3/pay/transactions/h5        （必须传 scene_info）
     *   2. 请求头带 Authorization: WECHATPAY2-SHA256-RSA2048 签名串
     *   3. 超时处理：网络超时必须返回 UNKNOWN，由查证补偿兜底，绝不能判失败
     * </pre>
     */
    @Override
    protected PayResponse doPay(PayCommand command) {
        String tradeType = command.extraParams().getOrDefault("tradeType", "JSAPI");
        if ("JSAPI".equals(tradeType) && command.payerId() == null) {
            throw new IllegalArgumentException("微信 JSAPI 支付必须传 payerId (openid)");
        }

        // TODO 真实实现：HTTP POST /v3/pay/transactions/{tradeType}
        //   body: {appid, mchid, description, out_trade_no, time_expire,
        //          notify_url, amount:{total: 分, currency:"CNY"}, payer:{openid}}
        //   返回 prepay_id / code_url / h5_url
        String prepayId = "wx" + System.currentTimeMillis();

        return PayResponse.pending(command.outTradeNo(), cred(
                "tradeType", tradeType,
                "prepayId", prepayId,
                "codeUrl", "weixin://wxpay/bizpayurl?pr=" + prepayId,
                "timeStamp", String.valueOf(Instant.now().getEpochSecond()),
                "nonceStr", command.outTradeNo().value(),
                // 真实环境这里必须是后端用商户私钥对 (appId,timeStamp,nonceStr,package) 计算的签名
                "paySign", "SIGN_MOCK"
        ));
    }

    /**
     * 查证。
     *
     * <p>微信查单接口 {@code GET /v3/pay/transactions/out-trade-no/{out_trade_no}}。
     * <b>关键：查单返回 404 时不能直接判失败。</b>
     * 下单请求可能尚未到达微信，需结合下单时间判断是否超过创建延迟窗口。
     */
    @Override
    protected QueryResponse doQuery(QueryCommand command) {
        // TODO 真实实现：GET /v3/pay/transactions/out-trade-no/{outTradeNo}?mchid={mchid}
        return QueryResponse.unknown(command.outTradeNo(), "Mock: 未实现真实查证");
    }

    @Override
    protected CloseResponse doClose(CloseCommand command) {
        // TODO 真实实现：POST /v3/pay/transactions/out-trade-no/{outTradeNo}/close
        return CloseResponse.success(command.outTradeNo());
    }

    /**
     * 退款。
     *
     * <p>注意：微信退款同步返回 SUCCESS 只代表<b>受理成功</b>，
     * 实际到账结果通过 {@code /v3/refund/domestic/refunds} 的回调通知，
     * 退款单必须保留"退款中"状态并做查证补偿。
     */
    @Override
    protected RefundResponse doRefund(RefundCommand command) {
        // TODO 真实实现：POST /v3/refund/domestic/refunds
        //   body: {out_trade_no, out_refund_no, reason,
        //          amount:{refund: 分, total: 分, currency:"CNY"}}
        return RefundResponse.succeeded(command.outRefundNo(), "RF" + System.currentTimeMillis(),
                command.amount());
    }

    /**
     * 回调解析。
     *
     * <p>严格顺序：<b>验签 → 解密 → 校验金额</b>。
     * 任何一步失败都必须拒绝，尤其是验签失败绝不能"先放行再排查"。
     */
    @Override
    public NotificationParseResult parseNotification(RawNotification raw) {
        // 步骤一：取验签头
        String serial = raw.headerIgnoreCase("Wechatpay-Serial");
        String signature = raw.headerIgnoreCase("Wechatpay-Signature");
        String timestamp = raw.headerIgnoreCase("Wechatpay-Timestamp");
        String nonce = raw.headerIgnoreCase("Wechatpay-Nonce");

        // 步骤二：用 serial 对应的平台证书验签（证书需定期下载更新）
        verifySignature(raw.body(), signature, timestamp, nonce, serial);

        // 步骤三：AES-256-GCM 解密 resource 字段
        String plain = decryptResource(raw.body());

        // 步骤四：映射为归一化结果
        return new NotificationParseResult(
                OutTradeNo.of(extractJson(plain, "out_trade_no")),
                extractJson(plain, "transaction_id"),
                mapStatus(extractJson(plain, "trade_state")),
                extractJson(plain, "trade_state"),
                Money.ofMinor(Long.parseLong(extractJson(plain, "amount.total")), Currency.CNY),
                extractJson(plain, "id"),   // 微信通知唯一 ID，用于去重
                "payment",
                Instant.now(),
                raw.body()
        );
    }

    private ChannelResultStatus mapStatus(String tradeState) {
        return switch (tradeState == null ? "" : tradeState) {
            case "SUCCESS" -> ChannelResultStatus.SUCCEEDED;
            case "CLOSED", "REVOKED", "PAYERROR" -> ChannelResultStatus.FAILED;
            case "NOTPAY", "USERPAYING" -> ChannelResultStatus.PENDING;
            default -> ChannelResultStatus.UNKNOWN;
        };
    }

    private void verifySignature(String body, String sig, String ts, String nonce, String serial) {
        if (sig == null || serial == null) {
            throw new SecurityException("微信回调缺少验签头，拒绝处理");
        }
        // TODO 真实实现：用 serial 对应平台证书做 SHA256withRSA 验签
        //   常见 bug：证书过期未更新导致全量验签失败
    }

    private String decryptResource(String body) {
        // TODO 真实实现：AES-256-GCM 解密 resource.ciphertext
        return body;
    }

    private String extractJson(String json, String path) {
        return "MOCK";
    }

    /** 微信 out_trade_no 长度上限 32 位 */
    @Override
    protected int maxOutTradeNoLength() { return 32; }


}
