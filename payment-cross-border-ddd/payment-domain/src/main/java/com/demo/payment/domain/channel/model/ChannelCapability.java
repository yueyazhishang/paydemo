package com.demo.payment.domain.channel.model;

import com.demo.payment.shared.money.Currency;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;

/**
 * 通道能力矩阵 —— 整套抽象里最核心的一个类。
 *
 * <h3>为什么必须有它</h3>
 * <p>9 个通道的差异不是"字段长得不一样"，而是<b>语义能力不一样</b>。如果只做字段映射，
 * 上层就会写成 "if (channel == WECHAT) {...} else if (channel == STRIPE) {...}"，
 * 每加一个通道就要改一遍业务代码 —— 这就是抽象泄漏。
 *
 * <p>正确做法是：<b>把差异声明成数据</b>，让上层用"能力查询"代替"类型判断"。
 *
 * <h3>几个决定性的能力差异（也是面试高频考点）</h3>
 * <ol>
 *   <li>{@code authCaptureSeparated}：<b>两段式</b>（Stripe/Worldpay/PayPal 可授权后请款）
 *       vs <b>一段式</b>（微信/支付宝下单即扣款，没有独立的 capture）。
 *       这直接决定支付单状态机是否要有 CAPTURING/CAPTURED 态。</li>
 *   <li>{@code supportsCancel}：撤销（当日未清算，原路退回、不产生退款单）
 *       vs 只能退款（退款是另一笔独立交易）。国内通道基本只有退款，没有撤销。</li>
 *   <li>{@code refundWindowDays}：微信/支付宝退款期限通常 365 天；Antom 下 BNPL 类
 *       支付方式只有 90~120 天。超期必须走人工差错流程，系统需要提前拦。</li>
 *   <li>{@code supportsChargeback}：<b>拒付是卡组织独有的</b>。PayPal/Stripe/Worldpay 有，
 *       微信/支付宝/京东完全没有这个概念（国内是"投诉/争议"，流程完全不同）。</li>
 *   <li>{@code notifyMode}：国内强依赖异步回调；Stripe/PayPal 是 Webhook（可能延迟、可能重投、
 *       可能乱序），因此必须<b>以主动查证为准、回调仅作触发器</b>。</li>
 *   <li>{@code idempotencyMode}：Stripe 支持 {@code Idempotency-Key} 请求头，PayPal 用
 *       {@code PayPal-Request-Id}，Antom 用 {@code paymentRequestId} 幂等，
 *       而<b>微信/支付宝没有幂等头</b>，只能靠 out_trade_no 唯一性兜底 —— 重试策略必须分开写。</li>
 * </ol>
 */
public record ChannelCapability(

        /** 通道编码 */
        ChannelCode channelCode,

        /** 通道展示名 */
        String displayName,

        /** 收单模式：聚合收单 / 直连银行 / 钱包收单 */
        AcquiringModel acquiringModel,

        /** 该通道能承载的支付方式集合（关键：Apple Pay 只是其中一种支付方式，不是通道） */
        Set<PaymentMethodType> supportedPaymentMethods,

        /** 是否为两段式（授权 + 请款分离） */
        boolean authCaptureSeparated,

        /** 是否支持撤销（cancel/void），区别于退款 */
        boolean supportsCancel,

        /** 是否支持部分退款 */
        boolean supportsPartialRefund,

        /** 是否支持多次部分退款（累计不超过原额）。部分通道只允许退一次 */
        boolean supportsMultiplePartialRefund,

        /** 退款有效期（天）。{@code null} 表示无限制 */
        Integer refundWindowDays,

        /** 是否支持拒付/争议（chargeback / dispute） */
        boolean supportsChargeback,

        /** 结果通知方式 */
        NotifyMode notifyMode,

        /** 通道侧幂等机制 */
        IdempotencyMode idempotencyMode,

        /** 签名算法 */
        SignatureAlgorithm signatureAlgorithm,

        /** 证书是否需要轮换（微信 v3 平台证书会自动轮换，必须实现自动下载） */
        boolean certificateAutoRotation,

        /** 接入模式（决定前端交互形态：跳转 / SDK / 组件 / 纯 API） */
        Set<IntegrationMode> integrationModes,

        /** 支持的币种 */
        Set<Currency> supportedCurrencies,

        /** 单笔最小/最大金额（按通道主要币种的最小单位计） */
        long minAmountMinor,
        long maxAmountMinor,

        /** 支付链接/凭证有效期。国内扫码支付通常 2 小时；Stripe PaymentIntent 默认 24 小时 */
        Duration credentialTtl,

        /** 通道是否提供官方沙箱环境 */
        boolean sandboxAvailable,

        /** 结算模式：即时入账 / T+1 延迟清算 */
        SettlementMode settlementMode

) {
    public ChannelCapability {
        supportedPaymentMethods = supportedPaymentMethods.isEmpty()
                ? EnumSet.noneOf(PaymentMethodType.class)
                : EnumSet.copyOf(supportedPaymentMethods);
        integrationModes = integrationModes.isEmpty()
                ? EnumSet.noneOf(IntegrationMode.class)
                : EnumSet.copyOf(integrationModes);
        supportedCurrencies = supportedCurrencies.isEmpty()
                ? EnumSet.noneOf(Currency.class)
                : EnumSet.copyOf(supportedCurrencies);
    }

    // ---------- 能力查询：上层业务用这些方法代替 instanceof / switch ----------

    /** 该通道能否承载指定支付方式 —— 智能路由的核心判断依据 */
    public boolean supports(PaymentMethodType paymentMethod) {
        return supportedPaymentMethods.contains(paymentMethod);
    }

    public boolean supports(Currency currency) {
        return supportedCurrencies.contains(currency);
    }

    /** 是否需要在支付成功后发起独立的请款动作 */
    public boolean requiresExplicitCapture() {
        return authCaptureSeparated;
    }

    /** 退款是否仍然在有效期内 */
    public boolean isRefundableAfterDays(int daysSincePaid) {
        return refundWindowDays == null || daysSincePaid <= refundWindowDays;
    }

    /** 是否只能通过主动查证获取终态（回调不可信/不存在时必须轮询） */
    public boolean requiresActiveQuery() {
        return notifyMode == NotifyMode.PULL_ONLY || notifyMode == NotifyMode.PUSH_AND_PULL;
    }

    /** 金额是否超出通道限额 */
    public boolean isAmountInRange(long amountMinor) {
        return amountMinor >= minAmountMinor && amountMinor <= maxAmountMinor;
    }

    // ---------- 枚举定义 ----------

    /** 收单模式 */
    public enum AcquiringModel {
        /** 聚合收单：本身支持多种支付方式，如 Antom（300+ 支付方式）、支付宝 */
        AGGREGATOR,
        /** 钱包收单：微信、支付宝、PayPal 钱包余额 */
        WALLET,
        /** 卡收单：Stripe、Worldpay（走卡组织网络） */
        CARD_ACQUIRING,
        /** 网关/银行直连：京东支付、银联 */
        GATEWAY,
        /** 凭证网络：Apple Pay —— 只产出 token，必须委托给 CARD_ACQUIRING 类通道执行 */
        CREDENTIAL_NETWORK
    }

    /** 结果通知方式 */
    public enum NotifyMode {
        /** 只有异步回调（微信、支付宝） */
        PUSH_ONLY,
        /** 只有主动查证（部分银行网关） */
        PULL_ONLY,
        /** 回调 + 查证双保险（生产环境应当一律按此处理，回调仅作触发器） */
        PUSH_AND_PULL
    }

    /** 通道侧幂等机制 —— 决定重试时是否需要携带幂等键 */
    public enum IdempotencyMode {
        /** 请求头 {@code Idempotency-Key}，24h 内同键返回首次结果（Stripe） */
        HEADER_IDEMPOTENCY_KEY,
        /** 请求头 {@code PayPal-Request-Id}（PayPal） */
        HEADER_REQUEST_ID,
        /** 业务字段幂等，如 Antom 的 {@code paymentRequestId} */
        BUSINESS_FIELD,
        /** 无幂等机制，只能靠商户订单号唯一性兜底（微信、支付宝） */
        MERCHANT_ORDER_NO_ONLY
    }

    /** 签名算法 */
    public enum SignatureAlgorithm {
        /** 微信支付 v3：RSA-SHA256 加平台证书序列号 */
        WECHATPAY_RSA_SHA256,
        /** 支付宝：RSA2 (SHA256withRSA) */
        ALIPAY_RSA2,
        /** PayPal / Antom：非对称或 HMAC-SHA256 */
        HMAC_SHA256,
        /** Stripe Webhook：HMAC-SHA256，带时间戳防重放 */
        STRIPE_WEBHOOK_HMAC,
        /** Worldpay：基于 XML 的 MAC / 商家密钥 */
        WORLDPAY_MAC,
        /** Apple Pay：由收单行验证，本系统不直接验签 */
        DELEGATED_TO_PSP
    }

    /** 接入模式 */
    public enum IntegrationMode {
        /** 纯 API（服务端对服务端，如 Apple Pay / 卡支付直连） */
        API_ONLY,
        /** 跳转托管收银台（支付宝 PC 支付、PayPal 标准版、Antom Checkout Page） */
        REDIRECT_CHECKOUT,
        /** 内嵌支付组件（Stripe Elements、Antom Payment Element） */
        EMBEDDED_ELEMENT,
        /** 原生 SDK 唤起（微信/支付宝 App 支付、Apple Pay） */
        NATIVE_SDK,
        /** 出示二维码（微信 Native、支付宝当面付） */
        QR_CODE
    }

    /** 结算模式 */
    public enum SettlementMode {
        /** 实时入账 */
        IMMEDIATE,
        /** 延迟清算（T+1 / T+N），需要独立的结算上下文处理 */
        DEFERRED
    }
}
