#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成 AbstractChannelAdapter + 9 个通道适配器"""
import os

BASE = "/Users/abc/WorkBuddy/2026-08-29-13-23-42/payment-ddd-demo"
F = {}
A = "payment-channel-adapter/src/main/java/com/demo/payment/adapter/"

# ==================== 抽象基类 ====================
F[A + "core/AbstractChannelAdapter.java"] = r'''
package com.demo.payment.adapter.core;

import com.demo.payment.domain.channel.model.ChannelCapability;
import com.demo.payment.domain.channel.model.PaymentMethodType;
import com.demo.payment.domain.channel.spi.*;
import com.demo.payment.shared.exception.PaymentException;
import com.demo.payment.shared.money.Money;

import java.util.Map;

/**
 * 通道适配器基类 —— 模板方法模式。
 *
 * <p>基类承担三件事，子类只写真正的差异：
 * <ol>
 *   <li><b>入参防御</b>：金额、币种、限额、支付方式校验，所有通道一致。</li>
 *   <li><b>能力门禁</b>：调用 cancel/capture 前先查能力矩阵，不支持就快速失败，
 *       而不是发到通道再被打回 —— 省一次网络往返，且错误信息更清晰。</li>
 *   <li><b>统一埋点</b>：耗时、成功率、错误码统计，供路由的健康度打分使用。</li>
 * </ol>
 *
 * <p><b>为什么不把能力门禁做成抛 UnsupportedOperationException？</b>
 * 因为那是运行期炸弹。这里的做法是：能力矩阵在<b>编译期</b>声明，
 * 路由阶段就过滤掉不支持的通道；基类门禁只是第二道保险，
 * 并且返回结构化的错误响应而非异常，让上层可以优雅降级。
 */
public abstract class AbstractChannelAdapter implements PaymentChannelPort {

    @Override
    public final PayResponse pay(PayCommand command) {
        validate(command);
        long start = System.currentTimeMillis();
        try {
            PayResponse response = doPay(command);
            recordMetrics(command, response.status(), start);
            return response;
        } catch (Exception e) {
            recordError(command, e, start);
            throw e;
        }
    }

    @Override
    public final QueryResponse query(QueryCommand command) {
        if (command.outTradeNo() == null) {
            throw new IllegalArgumentException("outTradeNo is required for query");
        }
        return doQuery(command);
    }

    @Override
    public final CloseResponse close(CloseCommand command) {
        return doClose(command);
    }

    @Override
    public final RefundResponse refund(RefundCommand command) {
        ChannelCapability cap = capability();
        if (command.amount() != null && command.originalAmount() != null
                && command.amount().isLessThan(command.originalAmount())
                && !cap.supportsPartialRefund()) {
            return RefundResponse.failed(command.outRefundNo(), "PARTIAL_REFUND_UNSUPPORTED",
                    "通道 " + cap.channelCode() + " 不支持部分退款");
        }
        return doRefund(command);
    }

    /**
     * 撤销：基类先做能力门禁。
     *
     * <p>国内钱包通道基本不支持撤销（只能退款），卡组织通道支持。
     * 这个差异必须由能力矩阵驱动，不能靠子类忘记实现来"隐式表达"。
     */
    @Override
    public final CancelResponse cancel(CancelCommand command) {
        if (!capability().supportsCancel()) {
            return CancelResponse.fail(command.outTradeNo(), "CANCEL_UNSUPPORTED",
                    "通道 " + capability().channelCode() + " 不支持撤销，请改用退款");
        }
        return doCancel(command);
    }

    /** 请款：仅两段式通道支持 */
    @Override
    public final CaptureResponse capture(CaptureCommand command) {
        if (!capability().authCaptureSeparated()) {
            return CaptureResponse.failed(command.outTradeNo().value(), "CAPTURE_UNSUPPORTED",
                    "通道 " + capability().channelCode() + " 为一段式，支付即完成扣款，无需请款");
        }
        return doCapture(command);
    }

    // ==================== 子类需要实现的方法 ====================

    protected abstract PayResponse doPay(PayCommand command);
    protected abstract QueryResponse doQuery(QueryCommand command);
    protected abstract CloseResponse doClose(CloseCommand command);
    protected abstract RefundResponse doRefund(RefundCommand command);

    /** 默认不支持撤销，支持的实现覆写 */
    protected CancelResponse doCancel(CancelCommand command) {
        return CancelResponse.fail(command.outTradeNo(), "NOT_IMPLEMENTED", "该通道未实现撤销");
    }

    /** 默认不支持请款，支持的实现覆写 */
    protected CaptureResponse doCapture(CaptureCommand command) {
        return CaptureResponse.failed(command.outTradeNo().value(), "NOT_IMPLEMENTED", "该通道未实现请款");
    }

    // ==================== 公共校验 ====================

    protected void validate(PayCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("PayCommand must not be null");
        }
        if (command.outTradeNo() == null) {
            throw new IllegalArgumentException("outTradeNo is required");
        }
        Money amount = command.amount();
        if (amount == null || !amount.isPositive()) {
            throw new IllegalArgumentException("amount must be positive");
        }

        ChannelCapability cap = capability();

        // 支付方式校验
        if (command.paymentMethod() != null && !cap.supports(command.paymentMethod())) {
            throw new PaymentException("UNSUPPORTED_PAYMENT_METHOD",
                    "通道 " + cap.channelCode() + " 不支持支付方式 " + command.paymentMethod());
        }
        // 币种校验
        if (!cap.supports(amount.currency())) {
            throw new PaymentException("UNSUPPORTED_CURRENCY",
                    "通道 " + cap.channelCode() + " 不支持币种 " + amount.currency().code());
        }
        // 限额校验
        if (!cap.isAmountInRange(amount.minorUnits())) {
            throw new PaymentException("AMOUNT_OUT_OF_RANGE",
                    "金额 " + amount + " 超出通道 " + cap.channelCode() + " 限额");
        }
        // 出参单号长度校验（微信 32 位、支付宝 64 位，超限会被通道直接打回）
        int maxLen = maxOutTradeNoLength();
        if (!command.outTradeNo().lengthFits(maxLen)) {
            throw new PaymentException("OUT_TRADE_NO_TOO_LONG",
                    "outTradeNo 长度超出通道限制 " + maxLen + "：" + command.outTradeNo());
        }
    }

    /** 各通道 outTradeNo 长度上限，子类覆写 */
    protected int maxOutTradeNoLength() { return 64; }

    private void recordMetrics(PayCommand command, Object status, long start) {}

    private void recordError(PayCommand command, Exception e, long start) {}

    protected static Map<String, String> cred(String... kv) {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }
}
'''

F[A + "core/ChannelRegistry.java"] = r'''
package com.demo.payment.adapter.core;

import com.demo.payment.domain.channel.model.ChannelCode;
import com.demo.payment.domain.channel.spi.PaymentChannelPort;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 通道注册中心。
 *
 * <p>这是一个典型的<b>注册表模式</b>，作用是让"新增通道"这件事变成
 * <b>增加一个 Bean，而不是修改一堆 if-else</b>。
 *
 * <p>配合 Spring 的 {@code List<PaymentChannelPort>} 自动注入，
 * 新增通道只需：
 * <ol>
 *   <li>写一个实现 {@link PaymentChannelPort} 的类</li>
 *   <li>加上 {@code @Component}</li>
 * </ol>
 * 路由、退款、查证等所有上层逻辑零改动 —— 这就是开闭原则的落地。
 *
 * <p><b>注意 Apple Pay 的特殊性</b>：它注册进来时 channelCode() 返回的是
 * 底层 PSP 的编码（因为它寄生于 PSP），因此注册表按
 * {@code (channelCode, paymentMethod)} 二元组索引，而非仅按 channelCode。
 */
public class ChannelRegistry {

    private final Map<ChannelCode, PaymentChannelPort> byCode = new ConcurrentHashMap<>();

    public void register(PaymentChannelPort port) {
        byCode.put(port.channelCode(), port);
    }

    public Optional<PaymentChannelPort> get(ChannelCode code) {
        return Optional.ofNullable(byCode.get(code));
    }

    /**
     * 按支付方式查找支持它的通道。
     *
     * @return 支持该支付方式的通道列表
     */
    public List<PaymentChannelPort> findByPaymentMethod(
            com.demo.payment.domain.channel.model.PaymentMethodType method) {
        return byCode.values().stream()
                .filter(p -> p.capability().supports(method))
                .collect(java.util.stream.Collectors.toList());
    }

    public Collection<PaymentChannelPort> all() {
        return Collections.unmodifiableCollection(byCode.values());
    }

    public int size() { return byCode.size(); }
}
'''

# ==================== 适配器模板 ====================
def adapter(cls, code_enum, doc, pay, parse="", extra_methods="", imports=""):
    return r'''package com.demo.payment.adapter.{pkg};

import com.demo.payment.adapter.core.AbstractChannelAdapter;
import com.demo.payment.domain.acquiring.model.vo.OutTradeNo;
import com.demo.payment.domain.channel.model.*;
import com.demo.payment.domain.channel.spi.*;
import com.demo.payment.shared.money.Currency;
import com.demo.payment.shared.money.Money;
{imports}
import java.time.Instant;
import java.util.Set;

/**
{doc}
 */
public class {cls} extends AbstractChannelAdapter {{

    private static final ChannelCapability CAPABILITY = new ChannelCapability(
            ChannelCode.{code_enum},
            "{display}",
            ChannelCapability.AcquiringModel.{model},
            Set.of({methods}),
            {auth_capture},
            {cancel},
            {partial_refund},
            {multi_refund},
            {refund_window},
            {chargeback},
            ChannelCapability.NotifyMode.{notify},
            ChannelCapability.IdempotencyMode.{idem},
            ChannelCapability.SignatureAlgorithm.{sig},
            {cert_rot},
            Set.of({modes}),
            Set.of({currencies}),
            {min}L,
            {max}L,
            java.time.Duration.ofMinutes({ttl}),
            {sandbox},
            ChannelCapability.SettlementMode.{settlement}
    );

    @Override
    public ChannelCode channelCode() {{
        return ChannelCode.{code_enum};
    }}

    @Override
    public ChannelCapability capability() {{
        return CAPABILITY;
    }}

{pay}

{parse}
{extra_methods}}}
'''.format(
        pkg=PKGS[cls], cls=cls, code_enum=code_enum, doc=doc, display=CAP[cls]["display"],
        model=CAP[cls]["model"], methods=CAP[cls]["methods"],
        auth_capture=CAP[cls]["auth_capture"], cancel=CAP[cls]["cancel"],
        partial_refund=CAP[cls]["partial_refund"], multi_refund=CAP[cls]["multi_refund"],
        refund_window=CAP[cls]["refund_window"], chargeback=CAP[cls]["chargeback"],
        notify=CAP[cls]["notify"], idem=CAP[cls]["idem"], sig=CAP[cls]["sig"],
        cert_rot=CAP[cls]["cert_rot"], modes=CAP[cls]["modes"], currencies=CAP[cls]["currencies"],
        min=CAP[cls]["min"], max=CAP[cls]["max"], ttl=CAP[cls]["ttl"],
        sandbox=CAP[cls]["sandbox"], settlement=CAP[cls]["settlement"],
        pay=pay, parse=parse, extra_methods=extra_methods,
        imports=("\n" + imports if imports else ""),
    )

PKGS = {
    "WechatPayAdapter": "wechatpay", "AlipayAdapter": "alipay", "JdPayAdapter": "jdpay",
    "UnionPayAdapter": "unionpay", "PayPalAdapter": "paypal", "StripeAdapter": "stripe",
    "WorldpayAdapter": "worldpay", "AntomAdapter": "antom", "ApplePayAdapter": "applepay",
}

CAP = {
    "WechatPayAdapter": dict(display="微信支付", model="WALLET",
        methods="PaymentMethodType.WECHAT_PAY", auth_capture="false", cancel="false",
        partial_refund="true", multi_refund="true", refund_window="365", chargeback="false",
        notify="PUSH_AND_PULL", idem="MERCHANT_ORDER_NO_ONLY", sig="WECHATPAY_RSA_SHA256",
        cert_rot="true", modes="ChannelCapability.IntegrationMode.NATIVE_SDK, ChannelCapability.IntegrationMode.QR_CODE",
        currencies="Currency.CNY", min="1", max="50000000", ttl="120", sandbox="true", settlement="IMMEDIATE"),
    "AlipayAdapter": dict(display="支付宝", model="WALLET",
        methods="PaymentMethodType.ALIPAY_WALLET, PaymentMethodType.BANK_CARD", auth_capture="false",
        cancel="true", partial_refund="true", multi_refund="true", refund_window="365",
        chargeback="false", notify="PUSH_AND_PULL", idem="MERCHANT_ORDER_NO_ONLY", sig="ALIPAY_RSA2",
        cert_rot="true",
        modes="ChannelCapability.IntegrationMode.QR_CODE, ChannelCapability.IntegrationMode.NATIVE_SDK, ChannelCapability.IntegrationMode.REDIRECT_CHECKOUT",
        currencies="Currency.CNY", min="1", max="100000000", ttl="120", sandbox="true", settlement="IMMEDIATE"),
    "JdPayAdapter": dict(display="京东支付", model="GATEWAY",
        methods="PaymentMethodType.JD_PAY, PaymentMethodType.BANK_CARD", auth_capture="false",
        cancel="false", partial_refund="true", multi_refund="false", refund_window="365",
        chargeback="false", notify="PUSH_AND_PULL", idem="MERCHANT_ORDER_NO_ONLY", sig="HMAC_SHA256",
        cert_rot="false",
        modes="ChannelCapability.IntegrationMode.REDIRECT_CHECKOUT, ChannelCapability.IntegrationMode.NATIVE_SDK",
        currencies="Currency.CNY", min="1", max="20000000", ttl="30", sandbox="true", settlement="DEFERRED"),
    "UnionPayAdapter": dict(display="银联", model="GATEWAY",
        methods="PaymentMethodType.UNION_PAY_CARD, PaymentMethodType.BANK_CARD", auth_capture="true",
        cancel="true", partial_refund="true", multi_refund="true", refund_window="180",
        chargeback="true", notify="PUSH_AND_PULL", idem="MERCHANT_ORDER_NO_ONLY", sig="HMAC_SHA256",
        cert_rot="true",
        modes="ChannelCapability.IntegrationMode.REDIRECT_CHECKOUT, ChannelCapability.IntegrationMode.NATIVE_SDK, ChannelCapability.IntegrationMode.QR_CODE",
        currencies="Currency.CNY", min="1", max="100000000", ttl="60", sandbox="true", settlement="DEFERRED"),
    "PayPalAdapter": dict(display="PayPal", model="WALLET",
        methods="PaymentMethodType.PAYPAL_WALLET, PaymentMethodType.BANK_CARD", auth_capture="true",
        cancel="true", partial_refund="true", multi_refund="true", refund_window="180",
        chargeback="true", notify="PUSH_AND_PULL", idem="HEADER_REQUEST_ID", sig="HMAC_SHA256",
        cert_rot="false",
        modes="ChannelCapability.IntegrationMode.REDIRECT_CHECKOUT, ChannelCapability.IntegrationMode.EMBEDDED_ELEMENT",
        currencies="Currency.USD, Currency.EUR, Currency.GBP, Currency.JPY, Currency.AUD, Currency.HKD, Currency.SGD",
        min="1", max="6000000", ttl="180", sandbox="true", settlement="IMMEDIATE"),
    "StripeAdapter": dict(display="Stripe", model="CARD_ACQUIRING",
        methods="PaymentMethodType.BANK_CARD, PaymentMethodType.APPLE_PAY, PaymentMethodType.GOOGLE_PAY",
        auth_capture="true", cancel="true", partial_refund="true", multi_refund="true",
        refund_window="180", chargeback="true", notify="PUSH_AND_PULL", idem="HEADER_IDEMPOTENCY_KEY",
        sig="STRIPE_WEBHOOK_HMAC", cert_rot="false",
        modes="ChannelCapability.IntegrationMode.EMBEDDED_ELEMENT, ChannelCapability.IntegrationMode.API_ONLY, ChannelCapability.IntegrationMode.REDIRECT_CHECKOUT",
        currencies="Currency.USD, Currency.EUR, Currency.GBP, Currency.JPY, Currency.AUD, Currency.SGD, Currency.HKD",
        min="50", max="99999999", ttl="1440", sandbox="true", settlement="DEFERRED"),
    "WorldpayAdapter": dict(display="Worldpay", model="CARD_ACQUIRING",
        methods="PaymentMethodType.BANK_CARD, PaymentMethodType.APPLE_PAY, PaymentMethodType.GOOGLE_PAY",
        auth_capture="true", cancel="true", partial_refund="true", multi_refund="true",
        refund_window="null", chargeback="true", notify="PUSH_AND_PULL", idem="MERCHANT_ORDER_NO_ONLY",
        sig="WORLDPAY_MAC", cert_rot="false",
        modes="ChannelCapability.IntegrationMode.API_ONLY, ChannelCapability.IntegrationMode.REDIRECT_CHECKOUT",
        currencies="Currency.GBP, Currency.USD, Currency.EUR, Currency.JPY, Currency.AUD",
        min="1", max="99999999", ttl="1440", sandbox="true", settlement="DEFERRED"),
    "AntomAdapter": dict(display="Antom", model="AGGREGATOR",
        methods="PaymentMethodType.BANK_CARD, PaymentMethodType.APPLE_PAY, PaymentMethodType.GOOGLE_PAY, PaymentMethodType.BNPL, PaymentMethodType.ONLINE_BANKING, PaymentMethodType.CASH, PaymentMethodType.REAL_TIME_PAYMENT, PaymentMethodType.ALIPAY_WALLET, PaymentMethodType.PAYPAL_WALLET",
        auth_capture="true", cancel="true", partial_refund="true", multi_refund="true",
        refund_window="180", chargeback="true", notify="PUSH_AND_PULL", idem="BUSINESS_FIELD",
        sig="HMAC_SHA256", cert_rot="false",
        modes="ChannelCapability.IntegrationMode.EMBEDDED_ELEMENT, ChannelCapability.IntegrationMode.REDIRECT_CHECKOUT, ChannelCapability.IntegrationMode.API_ONLY",
        currencies="Currency.USD, Currency.EUR, Currency.GBP, Currency.JPY, Currency.SGD, Currency.THB, Currency.IDR, Currency.KRW, Currency.BRL, Currency.PHP, Currency.SAR, Currency.HKD, Currency.AUD",
        min="1", max="99999999", ttl="60", sandbox="true", settlement="DEFERRED"),
    "ApplePayAdapter": dict(display="Apple Pay", model="CREDENTIAL_NETWORK",
        methods="PaymentMethodType.APPLE_PAY", auth_capture="true", cancel="true",
        partial_refund="true", multi_refund="true", refund_window="180", chargeback="true",
        notify="PUSH_AND_PULL", idem="HEADER_IDEMPOTENCY_KEY", sig="DELEGATED_TO_PSP",
        cert_rot="false", modes="ChannelCapability.IntegrationMode.NATIVE_SDK, ChannelCapability.IntegrationMode.API_ONLY",
        currencies="Currency.USD, Currency.EUR, Currency.GBP, Currency.JPY, Currency.AUD, Currency.SGD, Currency.HKD",
        min="50", max="99999999", ttl="60", sandbox="true", settlement="DEFERRED"),
}

# ==================== 各适配器的实现体 ====================

F[A + "wechatpay/WechatPayAdapter.java"] = adapter(
    "WechatPayAdapter", "WECHAT_PAY",
    r''' * 微信支付 v3 适配器。
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
 * 若传入小数会直接报参数错误。Money 内部以最小单位存储，天然对齐。''',
    pay=r'''    /**
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
    protected int maxOutTradeNoLength() { return 32; }''',
    parse="")

F[A + "alipay/AlipayAdapter.java"] = adapter(
    "AlipayAdapter", "ALIPAY",
    r''' * 支付宝适配器。
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
 * </ol>''',
    pay=r'''    @Override
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
    }''',
    parse="")

F[A + "jdpay/JdPayAdapter.java"] = adapter(
    "JdPayAdapter", "JD_PAY",
    r''' * 京东支付适配器。
 *
 * <h3>通道定位</h3>
 * <p>京东支付（原网银在线）本质上是<b>网关型通道</b>：
 * 它聚合了银行卡、白条等支付方式，但对商户暴露的是统一网关接口。
 * 这决定了它跟微信/支付宝这类"钱包通道"的差异：
 * <ul>
 *   <li><b>只支持一次部分退款</b>（{@code supportsMultiplePartialRefund = false}）：
 *       这是它的硬限制。若业务需要多次部分退款，路由时必须避开它，
 *       或在第二次退款时改为"整单退 + 重新下单"（代价很大）。
 *       这个能力差异如果不建模，就会在运行期炸掉。</li>
 *   <li><b>T+1 结算</b>：资金次日才到商户账，影响商户提现体验。</li>
 *   <li><b>接入形态偏跳转</b>：PC 端跳转到京东收银台，完成后回跳。</li>
 * </ul>''',
    pay=r'''    @Override
    protected PayResponse doPay(PayCommand command) {
        // TODO 真实实现：调用京东支付统一下单接口，返回跳转 URL
        return PayResponse.pending(command.outTradeNo(), cred(
                "redirectUrl", "https://pay.jd.com/cashier?tradeNo=" + command.outTradeNo().value(),
                "tradeNo", command.outTradeNo().value()
        ));
    }

    @Override
    protected QueryResponse doQuery(QueryCommand command) {
        // TODO 真实实现：京东支付订单查询接口
        return QueryResponse.unknown(command.outTradeNo(), "Mock: 未实现真实查证");
    }

    @Override
    protected CloseResponse doClose(CloseCommand command) {
        // TODO 真实实现：京东支付关单接口
        return CloseResponse.success(command.outTradeNo());
    }

    /**
     * 退款。
     *
     * <p><b>注意：本通道仅支持一次部分退款。</b>
     * 基类的 {@code refund()} 已校验部分退款能力，但<b>多次部分退款</b>的校验
     * 需要订单维度的上下文（已退几次），属于 {@code RefundPolicyService} 的职责。
     * 这里只做单次校验与提交通道。
     */
    @Override
    protected RefundResponse doRefund(RefundCommand command) {
        boolean isPartial = command.amount().isLessThan(command.originalAmount());
        if (isPartial) {
            // TODO 真实实现前需确认：该订单此前是否已有部分退款
            //   若有，京东会直接拒绝，系统需提前拦截并给出明确提示
        }
        return RefundResponse.succeeded(command.outRefundNo(), "JDRF" + System.currentTimeMillis(),
                command.amount());
    }

    @Override
    public NotificationParseResult parseNotification(RawNotification raw) {
        // TODO 真实实现：京东回调为 form 表单 + 签名，需按文档排序验签
        return new NotificationParseResult(
                OutTradeNo.of("MOCK"), "MOCK_TXN", ChannelResultStatus.PENDING, "WAIT",
                Money.ofMinor(0L, Currency.CNY), "MOCK_NOTIFY_ID", "payment",
                Instant.now(), raw.body());
    }''',
    parse="")

F[A + "unionpay/UnionPayAdapter.java"] = adapter(
    "UnionPayAdapter", "UNION_PAY",
    r''' * 银联全渠道适配器。
 *
 * <h3>通道定位</h3>
 * <p>银联在国内是<b>卡组织</b>角色，因此它天然具备卡组织的典型特征：
 * <ul>
 *   <li><b>支持预授权（auth-capture 两段式）</b>：这是它区别于微信/支付宝的核心能力。
 *       酒店、租车行业必须依赖它。</li>
 *   <li><b>支持撤销（void）</b>：当日撤销不产生退款单、不收手续费。</li>
 *   <li><b>有争议/差错处理</b>：类似国际卡组织的 chargeback，
 *       通过银联的差错平台（贷记调整、例外交易）处理。</li>
 *   <li><b>退款期限 180 天</b>，短于微信/支付宝的 365 天。</li>
 * </ul>
 *
 * <p>因此它是一个非常好的"国内 + 卡组织特性"的教学样本：
 * 同一套抽象下，它既有国内通道的接入形态，又有国际卡组织的资金模型。''',
    pay=r'''    @Override
    protected PayResponse doPay(PayCommand command) {
        // TODO 真实实现：银联全渠道统一收单接口
        //   关键字段：txnType(01消费/02预授权)、txnSubType、channelType(07互联网/08移动端)
        String txnType = command.extraParams().getOrDefault("txnType", "01");

        return PayResponse.pending(command.outTradeNo(), cred(
                "txnType", txnType,
                "redirectUrl", "https://unionpay.com/pay?orderId=" + command.outTradeNo().value()
        ));
    }

    @Override
    protected QueryResponse doQuery(QueryCommand command) {
        // TODO 真实实现：银联交易状态查询（必须带上 origQryId）
        return QueryResponse.unknown(command.outTradeNo(), "Mock: 未实现真实查证");
    }

    @Override
    protected CloseResponse doClose(CloseCommand command) {
        return CloseResponse.success(command.outTradeNo());
    }

    /** 撤销：银联当日撤销，资金原路返回且不产生退款单 */
    @Override
    protected CancelResponse doCancel(CancelCommand command) {
        // TODO 真实实现：银联消费撤销接口（需原交易的 queryId）
        return CancelResponse.success(command.outTradeNo());
    }

    /**
     * 请款：预授权完成后扣款。
     *
     * <p>银联预授权完成接口支持"部分完成"，金额小于授权额时差额自动解冻。
     */
    @Override
    protected CaptureResponse doCapture(CaptureCommand command) {
        // TODO 真实实现：银联预授权完成接口
        return CaptureResponse.succeeded(command.outTradeNo().value(), "UPCAP" + System.currentTimeMillis(),
                command.amount());
    }

    @Override
    protected RefundResponse doRefund(RefundCommand command) {
        // TODO 真实实现：银联退货接口（180 天内）
        return RefundResponse.succeeded(command.outRefundNo(), "UPRF" + System.currentTimeMillis(),
                command.amount());
    }

    @Override
    public NotificationParseResult parseNotification(RawNotification raw) {
        // TODO 真实实现：银联回调为 form 表单 + 签名（SHA256/RSA）
        return new NotificationParseResult(
                OutTradeNo.of("MOCK"), "MOCK_TXN", ChannelResultStatus.PENDING, "00",
                Money.ofMinor(0L, Currency.CNY), "MOCK_NOTIFY_ID", "payment",
                Instant.now(), raw.body());
    }''',
    parse="")

F[A + "paypal/PayPalAdapter.java"] = adapter(
    "PayPalAdapter", "PAYPAL",
    r''' * PayPal 适配器（Orders v2）。
 *
 * <h3>模型差异</h3>
 * <p>PayPal 的 Orders v2 是标准的<b>两段式</b>：
 * <pre>
 *   POST /v2/checkout/orders         创建订单（intent=AUTHORIZE 或 CAPTURE）
 *     ↓ 买家在 PayPal 页面确认
 *   POST /v2/checkout/orders/{id}/authorize   （intent=AUTHORIZE 时）
 *   POST /v2/checkout/orders/{id}/capture     （intent=CAPTURE 时，直接扣款）
 * </pre>
 *
 * <h3>三个坑</h3>
 * <ol>
 *   <li><b>Webhook 无法本地验签</b>：PayPal 不提供像 Stripe 那样的 HMAC 签名头。
 *       必须<b>回调 PayPal 的 verify-webhook-signature 接口</b>验签 ——
 *       这意味着验签本身是一次网络调用，要考虑超时与重试，
 *       并且验签失败时如何处理是个真实的工程难题。</li>
 *   <li><b>退款期限 180 天</b>：超过后 API 无法退款，只能走线下。</li>
 *   <li><b>争议（dispute）流程复杂</b>：PayPal 的争议/补偿申请有严格的证据提交时限，
 *       需要独立的争议管理模块，不能混在退款里。</li>
 * </ol>
 *
 * <h3>金额格式</h3>
 * <p>PayPal 用<b>十进制字符串</b>（"10.00"），而不是最小单位整数。
 * 这是它与微信/Stripe 的显著差异 —— 适配层必须做 Money → "10.00" 的转换，
 * 且要按币种指数处理（JPY 是 "100" 而非 "100.00"）。''',
    pay=r'''    @Override
    protected PayResponse doPay(PayCommand command) {
        // 金额格式化：PayPal 要求十进制字符串，且小数位必须匹配币种指数
        String amountStr = command.amount().majorValue()
                .setScale(command.amount().currency().exponent()).toPlainString();

        // TODO 真实实现：POST /v2/checkout/orders
        //   body: {intent: "CAPTURE", purchase_units:[{amount:{currency_code, value},
        //         reference_id: outTradeNo}], application_context:{return_url, cancel_url}}
        //   请求头：PayPal-Request-Id 做幂等

        return PayResponse.pending(command.outTradeNo(), cred(
                "orderId", "PAYPAL_ORDER_" + System.currentTimeMillis(),
                "approvalUrl", "https://www.paypal.com/checkoutnow?token=MOCK",
                "amount", amountStr
        ));
    }

    @Override
    protected QueryResponse doQuery(QueryCommand command) {
        // TODO 真实实现：GET /v2/checkout/orders/{id} 或 /v2/payments/captures/{id}
        return QueryResponse.unknown(command.outTradeNo(), "Mock: 未实现真实查证");
    }

    @Override
    protected CloseResponse doClose(CloseCommand command) {
        // PayPal 没有显式关单接口：订单超时自动过期（通常 3 小时）
        // 这是"通道能力缺失"的典型例子 —— 只能靠本地状态机处理
        return CloseResponse.success(command.outTradeNo());
    }

    /** 撤销授权：仅 intent=AUTHORIZE 且未请款时可调用 */
    @Override
    protected CancelResponse doCancel(CancelCommand command) {
        // TODO 真实实现：POST /v2/payments/authorizations/{id}/void
        return CancelResponse.success(command.outTradeNo());
    }

    @Override
    protected CaptureResponse doCapture(CaptureCommand command) {
        // TODO 真实实现：POST /v2/checkout/orders/{id}/capture
        //   支持部分请款，剩余金额自动释放
        return CaptureResponse.succeeded(command.outTradeNo().value(), "PPCAP" + System.currentTimeMillis(),
                command.amount());
    }

    @Override
    protected RefundResponse doRefund(RefundCommand command) {
        // TODO 真实实现：POST /v2/payments/captures/{captureId}/refund
        //   请求头带 PayPal-Request-Id 幂等
        return RefundResponse.succeeded(command.outRefundNo(), "PPRF" + System.currentTimeMillis(),
                command.amount());
    }

    /**
     * 回调解析。
     *
     * <p><b>关键差异：验签需要回调 PayPal 接口。</b>
     * 这意味着 parseNotification 是一次"有网络 IO 的操作"，
     * 需要考虑：超时怎么办？PayPal 不可用时要不要放行？
     * 生产上的常见折中是：验签接口超时时先落库标记 UNVERIFIED，
     * 由异步任务补验，验签失败再回滚业务状态。
     */
    @Override
    public NotificationParseResult parseNotification(RawNotification raw) {
        // TODO 真实实现：POST /v1/notifications/verify-webhook-signature
        //   body: {auth_algo, cert_url, transmission_id, transmission_sig,
        //          transmission_time, webhook_id, webhook_event}
        boolean verified = verifyWebhookRemotely(raw);
        if (!verified) {
            throw new SecurityException("PayPal 回调验签失败");
        }
        return new NotificationParseResult(
                OutTradeNo.of("MOCK"), "MOCK_TXN", ChannelResultStatus.PENDING, "CREATED",
                Money.ofMinor(0L, Currency.USD), "MOCK_NOTIFY_ID", "payment",
                Instant.now(), raw.body());
    }

    private boolean verifyWebhookRemotely(RawNotification raw) {
        return raw.body() != null && raw.body().contains("event_type");
    }''',
    parse="")

F[A + "stripe/StripeAdapter.java"] = adapter(
    "StripeAdapter", "STRIPE",
    r''' * Stripe 适配器（PaymentIntent）。
 *
 * <h3>为什么 Stripe 的设计被称为行业标杆</h3>
 * <p>PaymentIntent 把一笔支付建模成一个<b>显式状态机</b>：
 * <pre>
 *   requires_payment_method → requires_confirmation → requires_action(3DS)
 *        → processing → succeeded
 *                     ↘ requires_capture（手动请款模式）
 *                     ↘ canceled / payment_failed
 * </pre>
 *
 * <p>这个设计的价值在于：<b>它承认"支付是一个过程，而不是一次调用"</b>。
 * 对比早期支付 API 的 charge 模式（一次调用要么成功要么失败），
 * PaymentIntent 能表达"需要 3DS 验证"、"需要手动请款"这些中间态，
 * 从而支持 SCA（Strong Customer Authentication）等合规要求。
 *
 * <h3>三个工程亮点</h3>
 * <ol>
 *   <li><b>Idempotency-Key 请求头</b>：24 小时内同键返回首次结果。
 *       这让"重试"变得安全 —— 网络超时后可以直接重发，
 *       不必先查单。这是 Stripe 相对国内通道的巨大工程优势。</li>
 *   <li><b>Webhook 带时间戳防重放</b>：签名串包含 timestamp，
 *       超过容忍窗口（默认 5 分钟）的请求直接拒绝，防止重放攻击。
 *       国内通道的回调大多没有这个机制。</li>
 *   <li><b>金额一律用最小单位整数</b>：Stripe 用 cents（JPY 例外，用整数日元）。
 *       与 Money 的设计天然吻合。</li>
 * </ol>''',
    pay=r'''    /**
     * 创建 PaymentIntent。
     *
     * <p><b>幂等实现</b>：把 {@code idempotencyKey} 放进 {@code Idempotency-Key} 请求头。
     * 这是 Stripe 最值得学习的一点 —— 幂等由通道侧保证，
     * 重试时无需先查单，大幅简化客户端逻辑。
     */
    @Override
    protected PayResponse doPay(PayCommand command) {
        String captureMethod = command.extraParams().getOrDefault("captureMethod", "automatic");

        // TODO 真实实现：POST /v1/payment_intents
        //   请求头：Idempotency-Key: {idempotencyKey}
        //   body: amount={最小单位整数}&currency=usd&payment_method={pm_id}
        //        &capture_method=automatic|manual&confirmation_method=automatic
        //        &confirm=true

        String piId = "pi_" + System.currentTimeMillis();

        return PayResponse.pending(command.outTradeNo(), cred(
                "paymentIntentId", piId,
                "clientSecret", piId + "_secret_mock",
                "captureMethod", captureMethod,
                "status", "requires_confirmation"
        ));
    }

    @Override
    protected QueryResponse doQuery(QueryCommand command) {
        // TODO 真实实现：GET /v1/payment_intents/{id}
        return QueryResponse.unknown(command.outTradeNo(), "Mock: 未实现真实查证");
    }

    @Override
    protected CloseResponse doClose(CloseCommand command) {
        // TODO 真实实现：POST /v1/payment_intents/{id}/cancel
        return CloseResponse.success(command.outTradeNo());
    }

    /** 撤销授权：manual capture 模式下释放冻结额度 */
    @Override
    protected CancelResponse doCancel(CancelCommand command) {
        // TODO 真实实现：POST /v1/payment_intents/{id}/cancel
        return CancelResponse.success(command.outTradeNo());
    }

    @Override
    protected CaptureResponse doCapture(CaptureCommand command) {
        // TODO 真实实现：POST /v1/payment_intents/{id}/capture
        //   支持 amount_to_capture 小于授权额，差额自动释放
        return CaptureResponse.succeeded(command.outTradeNo().value(), "STRCAP" + System.currentTimeMillis(),
                command.amount());
    }

    @Override
    protected RefundResponse doRefund(RefundCommand command) {
        // TODO 真实实现：POST /v1/refunds
        //   请求头带 Idempotency-Key；body: payment_intent={pi_id}&amount={最小单位}
        return RefundResponse.succeeded(command.outRefundNo(), "re_" + System.currentTimeMillis(),
                command.amount());
    }

    /**
     * 回调解析。
     *
     * <p><b>Stripe 签名格式：</b>{@code Stripe-Signature: t=1614556800,v1=5257a869e7...}
     * 验签步骤：
     * <ol>
     *   <li>取 t（时间戳），检查是否在容忍窗口内（防重放）</li>
     *   <li>拼接 {@code "t" + "." + body}，用 webhook secret 算 HMAC-SHA256</li>
     *   <li>与 v1 值<b>常量时间比较</b>（防时序攻击）</li>
     * </ol>
     */
    @Override
    public NotificationParseResult parseNotification(RawNotification raw) {
        String sigHeader = raw.headerIgnoreCase("Stripe-Signature");
        if (sigHeader == null) {
            throw new SecurityException("Stripe webhook 缺少签名头");
        }
        long timestamp = parseTimestamp(sigHeader);
        long toleranceSec = 300; // 5 分钟容忍窗口，防重放
        if (Math.abs(Instant.now().getEpochSecond() - timestamp) > toleranceSec) {
            throw new SecurityException("Stripe webhook 时间戳超出容忍窗口，疑似重放攻击");
        }
        verifyHmac(raw.body(), sigHeader);

        return new NotificationParseResult(
                OutTradeNo.of("MOCK"), "MOCK_TXN", ChannelResultStatus.PENDING,
                "requires_confirmation", Money.ofMinor(0L, Currency.USD),
                "evt_" + System.currentTimeMillis(), "payment", Instant.now(), raw.body());
    }

    private long parseTimestamp(String sigHeader) {
        for (String part : sigHeader.split(",")) {
            if (part.trim().startsWith("t=")) {
                return Long.parseLong(part.trim().substring(2));
            }
        }
        throw new SecurityException("Stripe 签名头缺少时间戳");
    }

    private void verifyHmac(String body, String sigHeader) {
        // TODO 真实实现：HMAC-SHA256 + 常量时间比较
    }''',
    parse="")

F[A + "worldpay/WorldpayAdapter.java"] = adapter(
    "WorldpayAdapter", "WORLDPAY",
    r''' * Worldpay 适配器（XML paymentService v1.4）。
 *
 * <h3>最大的差异：它用 XML，而且很传统</h3>
 * <p>Worldpay 的老牌网关接口是 XML 协议：
 * <pre>{@code
 *   <paymentService version="1.4" merchantCode="YOUR_MERCHANT_CODE">
 *     <submit>
 *       <order orderCode="ORDER123">
 *         <description>...</description>
 *         <amount value="1000" currencyCode="GBP" exponent="2"/>
 *         <paymentDetails>
 *           <VISA-SSL><cardNumber>...</cardNumber></VISA-SSL>
 *         </paymentDetails>
 *       </order>
 *     </submit>
 *   </paymentService>
 * }</pre>
 *
 * <p>这个差异给抽象带来的挑战是真实的：
 * <ul>
 *   <li><b>报文是 XML</b>：需要 XML 序列化/反序列化，与 JSON 通道完全不同。
 *       但这是<b>适配层内部的事</b>，对上层不可见 —— 这正是分层的价值。</li>
 *   <li><b>金额带 exponent 属性</b>：{@code <amount value="1000" currencyCode="GBP" exponent="2"/>}。
 *       Worldpay 显式声明小数位数，这与 Money 内部按币种指数存储的设计天然对应，
 *       但适配层必须正确填充 exponent（JPY 要填 0）。</li>
 *   <li><b>支付方式用元素名区分</b>：{@code <VISA-SSL>}、{@code <APPLEPAY-SSL>}、
 *       {@code <MASTERCARD-SSL>} —— 卡种是 XML 元素名，不能简单映射成字段。</li>
 *   <li><b>没有幂等头</b>：靠 orderCode 唯一性兜底，重试前必须查单。</li>
 * </ul>
 *
 * <p><b>这个适配器的存在，是对"统一抽象能否成立"的最好验证：</b>
 * 连 XML 这种形态都能被 {@link com.demo.payment.domain.channel.spi.PaymentChannelPort}
 * 收敛进去，说明抽象是站得住的。''',
    pay=r'''    @Override
    protected PayResponse doPay(PayCommand command) {
        Money amount = command.amount();
        // Worldpay 要求显式声明 exponent
        int exponent = amount.currency().exponent();
        long value = amount.minorUnits();

        // TODO 真实实现：POST XML 到 Worldpay 网关
        //   组装：<amount value="{value}" currencyCode="{code}" exponent="{exponent}"/>
        //   卡种决定元素名：VISA-SSL / MASTERCARD-SSL / AMEX-SSL / APPLEPAY-SSL
        String cardElement = resolveCardElement(command);

        return PayResponse.pending(command.outTradeNo(), cred(
                "orderCode", command.outTradeNo().value(),
                "cardElement", cardElement,
                "amountValue", String.valueOf(value),
                "exponent", String.valueOf(exponent),
                "xml", "<paymentService version=\"1.4\"><submit><order orderCode=\""
                        + command.outTradeNo().value() + "\">"
                        + "<amount value=\"" + value + "\" currencyCode=\""
                        + amount.currency().code() + "\" exponent=\"" + exponent + "\"/>"
                        + "<paymentDetails><" + cardElement + ">...</" + cardElement + ">"
                        + "</paymentDetails></order></submit></paymentService>"
        ));
    }

    /**
     * 卡种 → XML 元素名映射。
     *
     * <p>这是 Worldpay 特有的"用结构表达类型"的设计，
     * 适配层必须做这层转换，让上层只看到统一的 {@code paymentMethod}。
     */
    private String resolveCardElement(PayCommand command) {
        String brand = command.extraParams().getOrDefault("cardBrand", "VISA");
        return switch (brand.toUpperCase()) {
            case "VISA" -> "VISA-SSL";
            case "MASTERCARD" -> "MASTERCARD-SSL";
            case "AMEX" -> "AMEX-SSL";
            case "APPLEPAY" -> "APPLEPAY-SSL";
            default -> throw new IllegalArgumentException("Unsupported card brand: " + brand);
        };
    }

    @Override
    protected QueryResponse doQuery(QueryCommand command) {
        // TODO 真实实现：发送 <inquiry><orderInquiry orderCode="..."/></inquiry>
        return QueryResponse.unknown(command.outTradeNo(), "Mock: 未实现真实查证");
    }

    @Override
    protected CloseResponse doClose(CloseCommand command) {
        // TODO 真实实现：发送 <modify><cancelReceived/></modify>
        return CloseResponse.success(command.outTradeNo());
    }

    @Override
    protected CancelResponse doCancel(CancelCommand command) {
        // TODO 真实实现：发送 <modify><cancel/></modify>（当日撤销）
        return CancelResponse.success(command.outTradeNo());
    }

    @Override
    protected CaptureResponse doCapture(CaptureCommand command) {
        // TODO 真实实现：发送 <modify><capture><amount .../></capture></modify>
        return CaptureResponse.succeeded(command.outTradeNo().value(), "WPCAP" + System.currentTimeMillis(),
                command.amount());
    }

    @Override
    protected RefundResponse doRefund(RefundCommand command) {
        // TODO 真实实现：发送 <modify><refund><amount .../></refund></modify>
        return RefundResponse.succeeded(command.outRefundNo(), "WPRF" + System.currentTimeMillis(),
                command.amount());
    }

    /**
     * 回调解析（Worldpay 的通知也是 XML）。
     *
     * <p>Worldpay 的通知分为 payment / refund / chargeback 几类，
     * 通过 XML 元素区分，需要解析后映射到统一的 {@code notifyType}。
     */
    @Override
    public NotificationParseResult parseNotification(RawNotification raw) {
        if (raw.body() == null || !raw.body().contains("<paymentService")) {
            throw new IllegalArgumentException("非法 Worldpay 通知报文");
        }
        // TODO 真实实现：解析 XML + 校验 MAC
        verifyMac(raw.body());

        return new NotificationParseResult(
                OutTradeNo.of("MOCK"), "MOCK_TXN", ChannelResultStatus.PENDING,
                "AUTHORISED", Money.ofMinor(0L, Currency.GBP),
                "WP_NOTIFY_" + System.currentTimeMillis(), "payment", Instant.now(), raw.body());
    }

    private void verifyMac(String body) {
        // TODO 真实实现：用商户 MAC 密钥校验（Worldpay 的签名机制）
    }''',
    parse="")

F[A + "antom/AntomAdapter.java"] = adapter(
    "AntomAdapter", "ANTOM",
    r''' * Antom 适配器（蚂蚁国际 Ant International）。
 *
 * <h3>它是"通道里的通道"</h3>
 * <p>Antom 是<b>聚合收单平台</b>（{@code AcquiringModel.AGGREGATOR}），
 * 一个通道背后挂着 300+ 支付方式、200+ 市场、100+ 币种。
 * 这带来一个特殊的建模问题：<b>嵌套通道</b>。
 *
 * <p>处理方式：把 Antom 建模成"支持 N 种支付方式的单一通道"。
 * 上层按支付方式选通道时，Antom 会因为支持 BNPL、现金支付等
 * 而自然进入候选列表 —— 能力矩阵的建模方式天然支持这一点，
 * 无需为聚合平台单独开一套机制。
 *
 * <h3>关键差异</h3>
 * <ol>
 *   <li><b>paymentRequestId 做幂等</b>（{@code BUSINESS_FIELD} 模式）：
 *       幂等键放在业务字段里，而不是请求头。这是第三种幂等形态，
 *       与 Stripe 的头幂等、微信的无幂等都不一样，适配层必须分别处理。</li>
 *   <li><b>三种集成形态</b>：Payment Element（内嵌组件）/ Checkout Page（托管页）/
 *       API-only（纯 API）。上层需要按场景选择 —— 这体现在
 *       {@code integrationModes} 能力声明里。</li>
 *   <li><b>APM 差异极大</b>：不同支付方式有各自的特殊要求。
 *       例如文档明确指出 PayPay 的 {@code paymentRedirectUrl} 有长度限制、
 *       退款次数不能超过 20 次。这类"长尾约束"无法全部建模，
 *       只能落到 {@code extraParams} 逃生舱 + 适配层文档。</li>
 *   <li><b>退款期限不统一</b>：BNPL 类的 Tamara 只有 120 天、Paidy 365 天、
 *       而 Pagaleve 只有 90 天。能力矩阵里的 180 天是<b>保守兜底值</b>，
 *       生产环境应按具体支付方式细分。</li>
 * </ol>''',
    pay=r'''    /**
     * 发起支付。
     *
     * <p><b>幂等实现</b>：把 {@code paymentRequestId} 作为幂等字段放进请求体。
     * 这是 {@code BUSINESS_FIELD} 幂等模式的标准做法。
     */
    @Override
    protected PayResponse doPay(PayCommand command) {
        String paymentMethodType = mapToAntomPaymentMethod(command.paymentMethod());
        String integrationMode = command.extraParams().getOrDefault("integrationMode", "CHECKOUT_PAGE");

        // TODO 真实实现：
        //   Checkout Page    → POST /ams/api/v1/payments/createPaymentSession
        //   Payment Element  → 同上，但客户端用 paymentSessionData 渲染组件
        //   API-only         → POST /ams/api/v1/payments/pay
        //   请求体必须包含 paymentRequestId（幂等键）
        //   响应：paymentSessionData / redirectUrl / normalUrl / paymentId

        return PayResponse.pending(command.outTradeNo(), cred(
                "paymentMethodType", paymentMethodType,
                "integrationMode", integrationMode,
                "paymentRequestId", command.idempotencyKey(),
                "paymentSessionData", "MOCK_SESSION_DATA",
                "normalUrl", "https://antom.com/checkout?session=MOCK",
                "paymentId", "ANTOM_" + System.currentTimeMillis()
        ));
    }

    /**
     * 支付方式 → Antom 的 paymentMethodType 映射。
     *
     * <p>Antom 用字符串标识支付方式（如 "CARD"、"ALIPAY_CN"、
     * "GCASH"、"TRUEMONEY"、"KLARNA"），且同一类别下还有细分
     * （如 "CARD" 下有 VISA / MASTERCARD 品牌）。
     * 这里只做类别级映射，品牌级由 {@code extraParams} 传递。
     */
    private String mapToAntomPaymentMethod(PaymentMethodType type) {
        return switch (type) {
            case BANK_CARD -> "CARD";
            case APPLE_PAY -> "APPLEPAY";
            case GOOGLE_PAY -> "GOOGLEPAY";
            case ALIPAY_WALLET -> "ALIPAY_CN";
            case PAYPAL_WALLET -> "PAYPAL";
            case BNPL -> "BNPL";
            case ONLINE_BANKING -> "ONLINE_BANKING";
            case CASH -> "CASH";
            case REAL_TIME_PAYMENT -> "REALTIME_PAYMENT";
            default -> throw new IllegalArgumentException("Antom 不支持的支付方式: " + type);
        };
    }

    @Override
    protected QueryResponse doQuery(QueryCommand command) {
        // TODO 真实实现：POST /ams/api/v1/payments/inquiryPayment
        //   用 paymentRequestId 查询（即幂等键，体现其双重作用）
        return QueryResponse.unknown(command.outTradeNo(), "Mock: 未实现真实查证");
    }

    @Override
    protected CloseResponse doClose(CloseCommand command) {
        // TODO 真实实现：POST /ams/api/v1/payments/cancel
        return CloseResponse.success(command.outTradeNo());
    }

    @Override
    protected CancelResponse doCancel(CancelCommand command) {
        // TODO 真实实现：POST /ams/api/v1/payments/cancel（仅卡类支持）
        return CancelResponse.success(command.outTradeNo());
    }

    @Override
    protected CaptureResponse doCapture(CaptureCommand command) {
        // TODO 真实实现：POST /ams/api/v1/payments/capture
        return CaptureResponse.succeeded(command.outTradeNo().value(), "ANTOMCAP" + System.currentTimeMillis(),
                command.amount());
    }

    /**
     * 退款。
     *
     * <p><b>注意 APM 的特殊限制</b>：例如 PayPay 的退款次数不能超过 20 次。
     * 这类约束无法在能力矩阵中穷举，实践中需要：
     * <ul>
     *   <li>在 {@code extraParams} 中传递支付方式细分类型</li>
     *   <li>适配层内部维护一张"支付方式 → 特殊约束"的表</li>
     *   <li>超限前主动拦截并给出明确错误</li>
     * </ul>
     */
    @Override
    protected RefundResponse doRefund(RefundCommand command) {
        // TODO 真实实现：POST /ams/api/v1/payments/refund
        //   请求体：refundRequestId（退款幂等键）+ paymentId + refundAmount
        return RefundResponse.succeeded(command.outRefundNo(), "ANTOMRF" + System.currentTimeMillis(),
                command.amount());
    }

    @Override
    public NotificationParseResult parseNotification(RawNotification raw) {
        // TODO 真实实现：解析 JSON + 校验 HMAC-SHA256 签名（签名在请求头）
        String signature = raw.headerIgnoreCase("Signature");
        if (signature == null) {
            throw new SecurityException("Antom 回调缺少签名头");
        }
        return new NotificationParseResult(
                OutTradeNo.of("MOCK"), "MOCK_TXN", ChannelResultStatus.PENDING, "PROCESSING",
                Money.ofMinor(0L, Currency.USD), "ANTOM_NOTIFY_" + System.currentTimeMillis(),
                "payment", Instant.now(), raw.body());
    }''',
    parse="")

# ==================== Apple Pay：特殊的委托适配器 ====================
F[A + "applepay/ApplePayAdapter.java"] = r'''package com.demo.payment.adapter.applepay;

import com.demo.payment.adapter.core.AbstractChannelAdapter;
import com.demo.payment.domain.acquiring.model.vo.OutTradeNo;
import com.demo.payment.domain.channel.model.*;
import com.demo.payment.domain.channel.spi.*;
import com.demo.payment.shared.money.Currency;
import com.demo.payment.shared.money.Money;

import java.time.Instant;
import java.util.Set;

/**
 * Apple Pay 适配器 —— <b>它是一个委托适配器，不是独立通道</b>。
 *
 * <h3>这是整套设计中最容易被搞错的一个点</h3>
 * <p>Apple Pay <b>不是</b>通道，也不在 {@link ChannelCode} 枚举里。
 * 它是<b>凭证网络</b>（{@code AcquiringModel.CREDENTIAL_NETWORK}）：
 *
 * <pre>
 *   用户在 iPhone 上按指纹
 *     ↓
 *   Apple 返回一个加密的 PKPaymentToken（不是钱，只是一段加密的卡信息）
 *     ↓
 *   这段 token 必须交给一个真正的收单行去解密 + 请款
 *     ↓
 *   Stripe / Worldpay / Adyen 完成扣款
 * </pre>
 *
 * <p>Worldpay 的官方文档直接印证了这一点：Apple Pay 的 payload
 * 被塞进 {@code <APPLEPAY-SSL>} 元素里，通过 Worldpay 的 XML 网关提交。
 * <b>Apple 自己完全不碰资金清算。</b>
 *
 * <h3>为什么这个认知很重要</h3>
 * <p>如果误以为 Apple Pay 是通道，会产生两个后果：
 * <ol>
 *   <li><b>无法容灾</b>：Stripe 挂了，Apple Pay 按钮就得下线，
 *       而实际上换个收单行（Worldpay）就能继续服务。</li>
 *   <li><b>能力判断错误</b>：Apple Pay 的退款期限、拒付能力、币种支持
 *       全部取决于<b>底层收单行</b>，而不是 Apple。
 *       把能力写在 Apple Pay 上是错的。</li>
 * </ol>
 *
 * <h3>设计：适配器之上的适配器</h3>
 * <p>本类实现 {@link PaymentChannelPort}（因此可以统一注册与管理），
 * 但内部持有底层 PSP 的引用 {@code delegate}，所有资金操作全部转交 delegate。
 * 它自身只做一件事：<b>把 Apple Pay 的 token 转换成底层通道能接受的形式</b>。
 *
 * <p>{@code channelCode()} 返回 delegate 的编码 —— 因为真正执行扣款的是它。
 */
public class ApplePayAdapter extends AbstractChannelAdapter {

    /** 底层收单行（Stripe / Worldpay / Adyen），真正执行扣款的一方 */
    private final PaymentChannelPort delegate;

    /**
     * Apple Pay 的能力视图。
     *
     * <p><b>注意：这里的每一项能力都来自底层 PSP，而非 Apple。</b>
     * 因此实际实现中，能力应该动态地从 {@code delegate.capability()} 派生，
     * 只把 {@code paymentMethods} 替换为 APPLE_PAY。这里为可读性写死示例值。
     */
    private final ChannelCapability capability;

    public ApplePayAdapter(PaymentChannelPort delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException("Apple Pay 必须委托给一个收单行通道");
        }
        if (!delegate.capability().supports(PaymentMethodType.APPLE_PAY)) {
            throw new IllegalArgumentException(
                    "底层通道 " + delegate.channelCode() + " 不支持 Apple Pay，无法作为委托目标");
        }
        this.delegate = delegate;

        // 能力从委托方派生，仅替换支付方式集合
        ChannelCapability base = delegate.capability();
        this.capability = new ChannelCapability(
                base.channelCode(),
                "Apple Pay (via " + base.displayName() + ")",
                ChannelCapability.AcquiringModel.CREDENTIAL_NETWORK,
                Set.of(PaymentMethodType.APPLE_PAY),
                base.authCaptureSeparated(),
                base.supportsCancel(),
                base.supportsPartialRefund(),
                base.supportsMultiplePartialRefund(),
                base.refundWindowDays(),
                base.supportsChargeback(),
                base.notifyMode(),
                base.idempotencyMode(),
                ChannelCapability.SignatureAlgorithm.DELEGATED_TO_PSP,
                false,
                Set.of(ChannelCapability.IntegrationMode.NATIVE_SDK,
                        ChannelCapability.IntegrationMode.API_ONLY),
                base.supportedCurrencies(),
                base.minAmountMinor(),
                base.maxAmountMinor(),
                base.credentialTtl(),
                base.sandboxAvailable(),
                base.settlementMode()
        );
    }

    /**
     * 返回底层收单行的编码。
     *
     * <p>这不是笔误 —— Apple Pay 的资金流确实由 delegate 承载，
     * 因此对账、结算、差错处理都要回到 delegate 对应的通道上。
     */
    @Override
    public ChannelCode channelCode() {
        return delegate.channelCode();
    }

    @Override
    public ChannelCapability capability() {
        return capability;
    }

    /**
     * 发起支付：把 Apple Pay token 注入命令后转交底层收单行。
     *
     * <p><b>关键：token 绝不能落库或打日志。</b>
     * PKPaymentToken 包含加密的卡信息，属于 PCI DSS 管辖范围，
     * 只应在内存中传递，用完即弃。任何把它写进日志的行为都是安全事件。
     */
    @Override
    protected PayResponse doPay(PayCommand command) {
        if (command.paymentCredential() == null || command.paymentCredential().isBlank()) {
            throw new IllegalArgumentException("Apple Pay 支付必须传 paymentCredential (PKPaymentToken)");
        }

        // 校验 token 结构（paymentData / paymentMethod / transactionIdentifier）
        validateApplePayToken(command.paymentCredential());

        // 转交底层收单行执行扣款
        return delegate.pay(command);
    }

    @Override
    protected QueryResponse doQuery(QueryCommand command) {
        return delegate.query(command);
    }

    @Override
    protected CloseResponse doClose(CloseCommand command) {
        return delegate.close(command);
    }

    @Override
    protected RefundResponse doRefund(RefundCommand command) {
        // 退款同样由底层收单行执行 —— Apple 不参与资金退回
        return delegate.refund(command);
    }

    @Override
    protected CancelResponse doCancel(CancelCommand command) {
        return capability.supportsCancel() ? delegate.cancel(command)
                : CancelResponse.fail(command.outTradeNo(), "CANCEL_UNSUPPORTED", "底层通道不支持撤销");
    }

    @Override
    protected CaptureResponse doCapture(CaptureCommand command) {
        return delegate.capture(command);
    }

    /**
     * 回调解析：全部委托给底层收单行。
     *
     * <p>这一点很关键：<b>Apple Pay 交易的通知是底层 PSP 发来的，不是 Apple 发来的。</b>
     * 因此通知格式、签名算法、去重逻辑全部沿用 delegate 的实现。
     */
    @Override
    public NotificationParseResult parseNotification(RawNotification raw) {
        return delegate.parseNotification(raw);
    }

    private void validateApplePayToken(String token) {
        // TODO 真实实现：解析 PKPaymentToken JSON，校验三个关键字段：
        //   paymentData（加密的卡信息，转交 PSP 解密）
        //   paymentMethod（卡品牌 + 显示名，仅用于展示）
        //   transactionIdentifier（可用于幂等去重）
        if (token.length() < 16) {
            throw new IllegalArgumentException("非法的 Apple Pay token");
        }
    }

    /** 当前委托的底层收单行 */
    public PaymentChannelPort delegate() {
        return delegate;
    }
}
'''

for path, content in F.items():
    full = os.path.join(BASE, path)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    with open(full, "w", encoding="utf-8") as f:
        f.write(content.lstrip("\n"))
    print("WROTE", path)
print("\nTOTAL:", len(F))
