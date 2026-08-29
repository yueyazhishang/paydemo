package com.zxpay.infrastructure.channel.config;

import com.zxpay.domain.channel.model.AmountConstraint;
import com.zxpay.domain.channel.model.AuthModel;
import com.zxpay.domain.channel.model.Capability;
import com.zxpay.domain.channel.model.ChannelCapability;
import com.zxpay.domain.channel.model.ChannelCode;
import com.zxpay.domain.channel.model.IdempotencySpec;
import com.zxpay.domain.channel.model.InteractionMode;
import com.zxpay.domain.channel.model.NotifySpec;
import com.zxpay.domain.channel.model.PaymentMethod;
import com.zxpay.domain.channel.model.RefundPolicy;
import com.zxpay.domain.channel.port.ChannelCapabilityQuery;
import com.zxpay.sharedkernel.money.Currency;
import com.zxpay.sharedkernel.money.Money;

import java.time.Duration;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 九家通道的能力矩阵配置。
 *
 * <p><b>这是整个 Demo 里最该被反复读的一个文件。</b>
 *
 * <p>它把「各通道差异」从代码分支变成了可查询的数据。业务代码全程只问
 * {@code capability.supports(XXX)}，从不写 {@code if (channel == WECHAT)}。
 * 接一家新通道 = 在这里加一份配置 + 写一个适配器，业务层一行不改。
 *
 * <h3>怎么读这份配置</h3>
 * <p>建议按「国内外对比」的视角横着看，重点关注四处差异：
 * <ol>
 *   <li><b>交易模型</b>：国内是 SALE（下单即扣款），海外是 AUTH_ONLY + CAPTURE（先授权后请款）。
 *       看 {@code capabilities} 里有没有 AUTH_ONLY / CAPTURE / VOID 就知道。</li>
 *   <li><b>退款窗口</b>：国内 365 天且即时到账，海外普遍 180 天且异步。
 *       看 {@code refundPolicy.refundWindow} 与 {@code instantRefund}。</li>
 *   <li><b>幂等维度</b>：国内用商户订单号，海外用请求头（Idempotency-Key）。
 *       看 {@code idempotencySpec.scope}。这一项直接决定了重试能否安全。</li>
 *   <li><b>角色分层</b>：国内第三方支付是「钱包+收单+清算」一体；
 *       海外是钱包 / PSP / 收单行 / 卡组织四层。
 *       看 {@code channel.category()}——Apple Pay 是 WALLET，银联是 SCHEME，
 *       两者 {@code isAcquirable()} 都为 false，会被路由自动排除。</li>
 * </ol>
 *
 * <p>生产环境这份数据应来自配置中心或数据库（支持热更新、灰度、按商户差异化），
 * 此处硬编码仅为教学可读。
 */
public class ChannelCapabilityConfiguration implements ChannelCapabilityQuery {

    private final Map<ChannelCode, ChannelCapability> capabilities;

    public ChannelCapabilityConfiguration() {
        this.capabilities = build();
    }

    @Override
    public Optional<ChannelCapability> findByChannel(ChannelCode channel) {
        return Optional.ofNullable(capabilities.get(channel));
    }

    @Override
    public Collection<ChannelCapability> findAllEnabled() {
        return capabilities.values().stream().filter(ChannelCapability::enabled).toList();
    }

    // =====================================================================
    // 九家通道
    // =====================================================================

    private Map<ChannelCode, ChannelCapability> build() {
        Map<ChannelCode, ChannelCapability> map = new EnumMap<>(ChannelCode.class);

        map.put(ChannelCode.WECHAT_PAY, wechatPay());
        map.put(ChannelCode.ALIPAY, alipay());
        map.put(ChannelCode.JD_PAY, jdPay());
        map.put(ChannelCode.UNIONPAY, unionPay());
        map.put(ChannelCode.STRIPE, stripe());
        map.put(ChannelCode.PAYPAL, paypal());
        map.put(ChannelCode.ANTOM, antom());
        map.put(ChannelCode.WORLDPAY, worldpay());
        map.put(ChannelCode.APPLE_PAY, applePay());

        return map;
    }

    // =====================================================================
    // 国内：一体化第三方支付
    // =====================================================================

    /**
     * 微信支付。
     *
     * <p>国内一体化通道的典型：钱包、收单、清算一体，没有「授权」概念。
     * 退款需要商户证书，且支持「撤销」这个海外没有的能力。
     */
    private ChannelCapability wechatPay() {
        return new ChannelCapability(
                ChannelCode.WECHAT_PAY,
                true,
                Set.of(PaymentMethod.WECHAT_JSAPI, PaymentMethod.WECHAT_MINI, PaymentMethod.WECHAT_APP,
                        PaymentMethod.WECHAT_H5, PaymentMethod.WECHAT_NATIVE, PaymentMethod.WECHAT_MICRO),
                Set.of(InteractionMode.FRONTEND_SDK, InteractionMode.SCAN_QR,
                        InteractionMode.REDIRECT, InteractionMode.BARCODE),
                Set.of(
                        // 交易模型：仅即时交易，不支持授权分离
                        Capability.SALE,
                        // 下单形态：四种都支持，是所有通道里形态最全的
                        Capability.FRONTEND_SDK_INVOKE, Capability.QR_PRECREATE,
                        Capability.HOSTED_REDIRECT, Capability.BARCODE_DIRECT, Capability.SERVER_TO_SERVER,
                        // 安全：APIv3 商户证书签名 + 回调签名
                        Capability.CERT_BASED_SIGN, Capability.WEBHOOK_SIGNATURE,
                        // 退款：全额/部分/多次部分 + 即时到账 + 可查 + 撤销
                        Capability.FULL_REFUND, Capability.PARTIAL_REFUND, Capability.MULTIPLE_PARTIAL_REFUND,
                        Capability.INSTANT_REFUND, Capability.REFUND_QUERY, Capability.REVERSE,
                        // 订单管理
                        Capability.ORDER_QUERY, Capability.ORDER_CLOSE,
                        // 通知
                        Capability.ASYNC_NOTIFY, Capability.NOTIFY_RETRY, Capability.NOTIFY_OUT_OF_ORDER,
                        // 增值：担保交易与分账是国内生态的强项
                        Capability.ESCROW, Capability.SETTLEMENT_SPLIT),
                AmountConstraintHolder.cny(0.01, 50000),
                // 退款：50 次部分退款上限、365 天窗口、即时到账、必须带证书
                new RefundPolicy(true, 50, Duration.ofDays(365), true, true, true, true),
                // 通知：15 次重试，间隔从 15 秒递增到 6 小时，总共覆盖约 24 小时
                NotifySpecHolder.push(15, Duration.ofHours(24), true),
                // 幂等：商户订单号即幂等键，重复下单返回原单（最友好的一类）
                new IdempotencySpec(IdempotencySpec.IdempotencyScope.MERCHANT_ORDER_NO,
                        null, IdempotencySpec.ConflictBehaviour.RETURN_ORIGINAL,
                        "微信以 out_trade_no 为幂等主键，同号重复下单返回原单"),
                AuthModel.MERCHANT_CERT,
                new ChannelCapability.SettlementLatency(Duration.ofDays(1), false, "T+1 自动提现到商户银行账户"),
                1);
    }

    /** 支付宝。与微信同属国内一体化通道，差异主要在签名模型（RSA2）与退款次数不限。 */
    private ChannelCapability alipay() {
        return new ChannelCapability(
                ChannelCode.ALIPAY,
                true,
                Set.of(PaymentMethod.ALIPAY_WAP, PaymentMethod.ALIPAY_PAGE, PaymentMethod.ALIPAY_APP,
                        PaymentMethod.ALIPAY_F2F, PaymentMethod.ALIPAY_FACE),
                Set.of(InteractionMode.FRONTEND_SDK, InteractionMode.SCAN_QR,
                        InteractionMode.REDIRECT, InteractionMode.BARCODE),
                Set.of(
                        Capability.SALE,
                        Capability.FRONTEND_SDK_INVOKE, Capability.QR_PRECREATE,
                        Capability.HOSTED_REDIRECT, Capability.BARCODE_DIRECT, Capability.SERVER_TO_SERVER,
                        Capability.ASYM_KEY_SIGN, Capability.WEBHOOK_SIGNATURE,
                        Capability.FULL_REFUND, Capability.PARTIAL_REFUND, Capability.MULTIPLE_PARTIAL_REFUND,
                        Capability.INSTANT_REFUND, Capability.REFUND_QUERY, Capability.REVERSE,
                        Capability.ORDER_QUERY, Capability.ORDER_CLOSE,
                        Capability.ASYNC_NOTIFY, Capability.NOTIFY_RETRY, Capability.NOTIFY_OUT_OF_ORDER,
                        Capability.ESCROW, Capability.SETTLEMENT_SPLIT),
                AmountConstraintHolder.cny(0.01, 100000),
                // 与微信的关键差异：不限部分退款次数（以累计金额为准）+ 退款不需证书
                new RefundPolicy(true, RefundPolicy.UNLIMITED, Duration.ofDays(365), true, false, true, true),
                NotifySpecHolder.push(8, Duration.ofHours(24), true),
                new IdempotencySpec(IdempotencySpec.IdempotencyScope.MERCHANT_ORDER_NO,
                        null, IdempotencySpec.ConflictBehaviour.RETURN_ORIGINAL,
                        "支付宝以 out_trade_no 为幂等主键"),
                AuthModel.RSA2_KEY_PAIR,
                new ChannelCapability.SettlementLatency(Duration.ofDays(1), false, "T+1 结算"),
                2);
    }

    /** 京东支付。能力集明显小于微信/支付宝：没有撤销、没有担保、没有分账。 */
    private ChannelCapability jdPay() {
        return new ChannelCapability(
                ChannelCode.JD_PAY,
                true,
                Set.of(PaymentMethod.JD_APP, PaymentMethod.JD_H5, PaymentMethod.JD_QR),
                Set.of(InteractionMode.FRONTEND_SDK, InteractionMode.SCAN_QR, InteractionMode.REDIRECT),
                Set.of(
                        Capability.SALE,
                        Capability.FRONTEND_SDK_INVOKE, Capability.QR_PRECREATE, Capability.HOSTED_REDIRECT,
                        Capability.ASYM_KEY_SIGN, Capability.WEBHOOK_SIGNATURE,
                        Capability.FULL_REFUND, Capability.PARTIAL_REFUND, Capability.MULTIPLE_PARTIAL_REFUND,
                        Capability.INSTANT_REFUND, Capability.REFUND_QUERY,
                        Capability.ORDER_QUERY, Capability.ORDER_CLOSE,
                        Capability.ASYNC_NOTIFY, Capability.NOTIFY_RETRY, Capability.NOTIFY_OUT_OF_ORDER),
                AmountConstraintHolder.cny(0.01, 50000),
                // 最多 10 次部分退款，比微信的 50 次更严格
                new RefundPolicy(true, 10, Duration.ofDays(365), true, false, true, true),
                NotifySpecHolder.push(6, Duration.ofHours(12), true),
                new IdempotencySpec(IdempotencySpec.IdempotencyScope.MERCHANT_ORDER_NO,
                        null, IdempotencySpec.ConflictBehaviour.RETURN_ORIGINAL, "京东以商户订单号幂等"),
                AuthModel.RSA2_KEY_PAIR,
                new ChannelCapability.SettlementLatency(Duration.ofDays(1), false, "T+1 结算"),
                5);
    }

    /**
     * 银联。
     *
     * <p><b>注意：它的角色是卡组织（SCHEME），不是收单通道。</b>
     * 因此 {@code isAcquirable()} 返回 false，能力矩阵会自动把它排除在路由之外。
     *
     * <p>保留这份配置是有意为之：它演示了「配置里有、但路由用不了」的情况。
     * 真实系统中，商户要接银联卡，必须走某家收单机构（银行或 Worldpay 这类），
     * 而不是直接对接卡组织。
     */
    private ChannelCapability unionPay() {
        return new ChannelCapability(
                ChannelCode.UNIONPAY,
                true,
                Set.of(PaymentMethod.UNIONPAY_CLOUD_QUICKPASS, PaymentMethod.UNIONPAY_GATEWAY),
                Set.of(InteractionMode.FRONTEND_SDK, InteractionMode.REDIRECT),
                Set.of(
                        // 卡体系：支持授权与请款分离，与国内第三方支付不同
                        Capability.SALE, Capability.AUTH_ONLY, Capability.CAPTURE, Capability.VOID,
                        Capability.FRONTEND_SDK_INVOKE, Capability.HOSTED_REDIRECT,
                        Capability.CERT_BASED_SIGN, Capability.WEBHOOK_SIGNATURE,
                        Capability.FULL_REFUND, Capability.PARTIAL_REFUND, Capability.REFUND_QUERY,
                        Capability.ORDER_QUERY,
                        Capability.ASYNC_NOTIFY, Capability.NOTIFY_RETRY,
                        Capability.MULTI_CURRENCY),
                AmountConstraintHolder.multi(
                        Map.of(Currency.CNY, List.of(0.01, 50000.0),
                                Currency.HKD, List.of(0.1, 60000.0),
                                Currency.USD, List.of(0.1, 8000.0))),
                // 卡组织惯例：180 天退款窗口，且退款异步（非即时）
                new RefundPolicy(true, RefundPolicy.UNLIMITED, Duration.ofDays(180), false, true, true, true),
                NotifySpecHolder.push(5, Duration.ofHours(24), true),
                new IdempotencySpec(IdempotencySpec.IdempotencyScope.MERCHANT_ORDER_NO,
                        null, IdempotencySpec.ConflictBehaviour.RETURN_ORIGINAL, "银联以商户订单号幂等"),
                AuthModel.MERCHANT_CERT,
                new ChannelCapability.SettlementLatency(Duration.ofDays(1), false, "按清算周期结算"),
                9);
    }

    // =====================================================================
    // 海外：PSP 与收单机构
    // =====================================================================

    /**
     * Stripe。
     *
     * <p>PSP 的典型代表：统一 API，内部聚合多家收单行、内置风控与 3DS。
     *
     * <p>与国内通道最核心的三处差异：
     * <ol>
     *   <li>支持完整的授权/请款/增量授权/撤销（{@code AUTH_ONLY / CAPTURE / INCREMENTAL_AUTH / VOID}）。</li>
     *   <li>幂等键是<b>请求头</b>而非订单号，有效期 24 小时，
     *       且<b>同键不同参数会直接报错</b>（{@code ConflictBehaviour.REJECT}）——
     *       这意味着重试必须复用完全相同的请求体。</li>
     *   <li>有完整的争议与拒付申诉流程（{@code DISPUTE / CHARGEBACK_REPRESENTMENT}），
     *       国内对应的是「投诉 + 平台介入」，资金流向完全不同。</li>
     * </ol>
     *
     * <p>注意它<b>不支持 REVERSE</b>：海外没有「撤销当日交易」这个动作，
     * 要撤销未请款的授权请用 VOID，已请款的只能退款。
     */
    private ChannelCapability stripe() {
        return new ChannelCapability(
                ChannelCode.STRIPE,
                true,
                Set.of(PaymentMethod.CARD, PaymentMethod.APPLE_PAY, PaymentMethod.GOOGLE_PAY,
                        PaymentMethod.SEPA_DEBIT, PaymentMethod.BANK_TRANSFER),
                Set.of(InteractionMode.FRONTEND_SDK, InteractionMode.API_ONLY,
                        InteractionMode.REDIRECT, InteractionMode.ASYNC_INSTRUCTION),
                Set.of(
                        // 交易模型：完整的两段式
                        Capability.SALE, Capability.AUTH_ONLY, Capability.CAPTURE,
                        Capability.PARTIAL_CAPTURE, Capability.INCREMENTAL_AUTH, Capability.VOID,
                        Capability.FRONTEND_SDK_INVOKE, Capability.HOSTED_REDIRECT, Capability.SERVER_TO_SERVER,
                        // 海外强监管下的必备能力
                        Capability.THREE_DS_CHALLENGE, Capability.NETWORK_TOKENIZATION,
                        Capability.WEBHOOK_SIGNATURE,
                        Capability.FULL_REFUND, Capability.PARTIAL_REFUND, Capability.MULTIPLE_PARTIAL_REFUND,
                        Capability.REFUND_QUERY,
                        Capability.ORDER_QUERY,
                        Capability.ASYNC_NOTIFY, Capability.NOTIFY_RETRY, Capability.NOTIFY_OUT_OF_ORDER,
                        Capability.MULTI_CURRENCY, Capability.PRESENTMENT_CURRENCY,
                        Capability.DISPUTE, Capability.CHARGEBACK_REPRESENTMENT,
                        Capability.RECURRING),
                AmountConstraintHolder.multi(
                        Map.of(Currency.USD, List.of(0.5, 999999.0),
                                Currency.EUR, List.of(0.5, 900000.0),
                                Currency.GBP, List.of(0.3, 800000.0),
                                Currency.SGD, List.of(0.5, 1300000.0),
                                Currency.HKD, List.of(4.0, 7800000.0),
                                // 零小数位币种：最小单位就是 1 日元，不能按 2 位处理
                                Currency.JPY, List.of(50.0, 9999999.0),
                                Currency.AUD, List.of(0.5, 1400000.0),
                                Currency.CAD, List.of(0.5, 1300000.0))),
                // 卡退款是异步的（instantRefund=false），且无硬性退款窗口
                new RefundPolicy(true, RefundPolicy.UNLIMITED, null, false, false, true, true),
                // Stripe webhook 重试 3 天（远少于国内通道的 15 次）
                NotifySpecHolder.push(3, Duration.ofDays(3), true),
                new IdempotencySpec(IdempotencySpec.IdempotencyScope.REQUEST_HEADER,
                        Duration.ofHours(24), IdempotencySpec.ConflictBehaviour.REJECT,
                        "Stripe 用 Idempotency-Key 请求头，24 小时有效；同键不同参数直接拒绝"),
                AuthModel.API_KEY,
                new ChannelCapability.SettlementLatency(Duration.ofDays(2), false, "默认 2 天滚动结算"),
                1);
    }

    /**
     * PayPal。兼具钱包与 PSP 属性：既有用户余额账户，也处理卡收单。
     *
     * <p>与国内最不同的一点：{@code originalMethodOnly = false}——
     * 退款可以退到用户的 PayPal 账户余额，而不必原路退回银行卡。
     * 国内通道基本都强制原路退回，这是用户体感上的明显差异。
     */
    private ChannelCapability paypal() {
        return new ChannelCapability(
                ChannelCode.PAYPAL,
                true,
                Set.of(PaymentMethod.PAYPAL_WALLET, PaymentMethod.PAYPAL_VAULT, PaymentMethod.CARD),
                Set.of(InteractionMode.REDIRECT, InteractionMode.API_ONLY),
                Set.of(
                        Capability.SALE, Capability.AUTH_ONLY, Capability.CAPTURE,
                        Capability.PARTIAL_CAPTURE, Capability.VOID,
                        Capability.HOSTED_REDIRECT, Capability.SERVER_TO_SERVER,
                        Capability.WEBHOOK_SIGNATURE,
                        Capability.FULL_REFUND, Capability.PARTIAL_REFUND, Capability.MULTIPLE_PARTIAL_REFUND,
                        Capability.REFUND_QUERY,
                        Capability.ORDER_QUERY,
                        Capability.ASYNC_NOTIFY, Capability.NOTIFY_RETRY, Capability.NOTIFY_OUT_OF_ORDER,
                        Capability.MULTI_CURRENCY,
                        Capability.DISPUTE, Capability.CHARGEBACK_REPRESENTMENT,
                        Capability.RECURRING),
                AmountConstraintHolder.multi(
                        Map.of(Currency.USD, List.of(0.01, 60000.0),
                                Currency.EUR, List.of(0.01, 55000.0),
                                Currency.GBP, List.of(0.01, 48000.0),
                                Currency.HKD, List.of(0.1, 460000.0),
                                Currency.SGD, List.of(0.01, 80000.0),
                                Currency.JPY, List.of(1.0, 8000000.0),
                                Currency.AUD, List.of(0.01, 90000.0),
                                Currency.CAD, List.of(0.01, 82000.0))),
                // 180 天退款窗口（PayPal 争议期也是 180 天，两者一致）
                new RefundPolicy(true, RefundPolicy.UNLIMITED, Duration.ofDays(180), false, false, false, true),
                NotifySpecHolder.push(6, Duration.ofDays(3), true),
                new IdempotencySpec(IdempotencySpec.IdempotencyScope.REQUEST_HEADER,
                        Duration.ofHours(24), IdempotencySpec.ConflictBehaviour.RETURN_ORIGINAL,
                        "PayPal 用 PayPal-Request-Id 请求头"),
                AuthModel.OAUTH2_CLIENT,
                new ChannelCapability.SettlementLatency(Duration.ofDays(1), false, "余额即时可用，提现 T+1"),
                2);
    }

    /**
     * Antom（蚂蚁国际）。蚂蚁面向海外市场的 PSP 品牌。
     *
     * <p>独特之处：同时覆盖国际卡与本地化支付方式，
     * 并保留了国内体系的签名习惯（RSA2），又具备海外的 3DS 与授权分离。
     * 是「国内技术栈 + 海外业务语义」的混合体。
     */
    private ChannelCapability antom() {
        return new ChannelCapability(
                ChannelCode.ANTOM,
                true,
                Set.of(PaymentMethod.CARD, PaymentMethod.APPLE_PAY, PaymentMethod.GOOGLE_PAY,
                        PaymentMethod.ALIPAY_WAP, PaymentMethod.PAYPAL_WALLET),
                Set.of(InteractionMode.FRONTEND_SDK, InteractionMode.API_ONLY,
                        InteractionMode.REDIRECT, InteractionMode.SCAN_QR),
                Set.of(
                        Capability.SALE, Capability.AUTH_ONLY, Capability.CAPTURE, Capability.VOID,
                        Capability.FRONTEND_SDK_INVOKE, Capability.HOSTED_REDIRECT,
                        Capability.QR_PRECREATE, Capability.SERVER_TO_SERVER,
                        Capability.THREE_DS_CHALLENGE, Capability.NETWORK_TOKENIZATION,
                        Capability.ASYM_KEY_SIGN, Capability.WEBHOOK_SIGNATURE,
                        Capability.FULL_REFUND, Capability.PARTIAL_REFUND, Capability.MULTIPLE_PARTIAL_REFUND,
                        Capability.REFUND_QUERY,
                        Capability.ORDER_QUERY, Capability.ORDER_CLOSE,
                        Capability.ASYNC_NOTIFY, Capability.NOTIFY_RETRY, Capability.NOTIFY_OUT_OF_ORDER,
                        Capability.MULTI_CURRENCY, Capability.PRESENTMENT_CURRENCY,
                        Capability.DISPUTE, Capability.RECURRING, Capability.SETTLEMENT_SPLIT),
                AmountConstraintHolder.multi(
                        Map.of(Currency.USD, List.of(0.01, 100000.0),
                                Currency.EUR, List.of(0.01, 90000.0),
                                Currency.GBP, List.of(0.01, 80000.0),
                                Currency.SGD, List.of(0.01, 130000.0),
                                Currency.HKD, List.of(0.1, 780000.0),
                                Currency.JPY, List.of(1.0, 15000000.0),
                                Currency.MYR, List.of(0.01, 440000.0),
                                Currency.THB, List.of(0.01, 3400000.0))),
                new RefundPolicy(true, RefundPolicy.UNLIMITED, Duration.ofDays(180), false, false, true, true),
                NotifySpecHolder.push(8, Duration.ofHours(24), true),
                new IdempotencySpec(IdempotencySpec.IdempotencyScope.REQUEST_HEADER,
                        Duration.ofHours(24), IdempotencySpec.ConflictBehaviour.RETURN_ORIGINAL,
                        "Antom 用请求头幂等键"),
                AuthModel.RSA2_KEY_PAIR,
                new ChannelCapability.SettlementLatency(Duration.ofDays(1), false, "T+1 结算"),
                3);
    }

    /**
     * Worldpay。老牌收单机构 / 网关，直接对接卡组织。
     *
     * <p>三处值得注意的限制：
     * <ol>
     *   <li>{@code idempotencySpec.conflictBehaviour = UNDEFINED}——
     *       <b>这是最危险的一类</b>：重试时通道的行为未定义，
     *       既可能返回原结果，也可能再扣一笔。必须靠我方主动查单兜底，
     *       不能依赖通道的幂等保护。</li>
     *   <li>{@code supportsRefundAfterSettlement = false}——
     *       资金已结算给商户后不能再退款，因此业务上要控制结算节奏。</li>
     *   <li>{@code NOTIFY_OUT_OF_ORDER} 缺失：该通道通知相对有序，
     *       但仍不能放松——「相对有序」不等于「保证有序」。</li>
     * </ol>
     */
    private ChannelCapability worldpay() {
        return new ChannelCapability(
                ChannelCode.WORLDPAY,
                true,
                Set.of(PaymentMethod.CARD, PaymentMethod.APPLE_PAY, PaymentMethod.GOOGLE_PAY),
                Set.of(InteractionMode.API_ONLY, InteractionMode.FRONTEND_SDK, InteractionMode.REDIRECT),
                Set.of(
                        Capability.SALE, Capability.AUTH_ONLY, Capability.CAPTURE,
                        Capability.PARTIAL_CAPTURE, Capability.VOID,
                        Capability.SERVER_TO_SERVER, Capability.FRONTEND_SDK_INVOKE, Capability.HOSTED_REDIRECT,
                        Capability.THREE_DS_CHALLENGE, Capability.NETWORK_TOKENIZATION,
                        Capability.WEBHOOK_SIGNATURE,
                        Capability.FULL_REFUND, Capability.PARTIAL_REFUND, Capability.REFUND_QUERY,
                        Capability.ORDER_QUERY,
                        Capability.ASYNC_NOTIFY, Capability.NOTIFY_RETRY,
                        Capability.MULTI_CURRENCY,
                        Capability.DISPUTE, Capability.CHARGEBACK_REPRESENTMENT),
                AmountConstraintHolder.multi(
                        Map.of(Currency.USD, List.of(0.01, 200000.0),
                                Currency.EUR, List.of(0.01, 180000.0),
                                Currency.GBP, List.of(0.01, 160000.0),
                                Currency.HKD, List.of(0.1, 1500000.0),
                                Currency.SGD, List.of(0.01, 270000.0),
                                Currency.AUD, List.of(0.01, 300000.0),
                                Currency.JPY, List.of(1.0, 30000000.0))),
                // 结算后不支持退款——老牌收单机构的常见限制
                new RefundPolicy(true, RefundPolicy.UNLIMITED, Duration.ofDays(180), false, false, true, false),
                NotifySpecHolder.push(5, Duration.ofHours(24), false),
                new IdempotencySpec(IdempotencySpec.IdempotencyScope.REQUEST_HEADER,
                        Duration.ofDays(7), IdempotencySpec.ConflictBehaviour.UNDEFINED,
                        "Worldpay 重试行为未定义，必须靠主动查单兜底，不可依赖通道幂等"),
                AuthModel.OAUTH2_CLIENT,
                new ChannelCapability.SettlementLatency(Duration.ofDays(3), false, "按收单协议结算，通常 T+3"),
                4);
    }

    /**
     * Apple Pay。
     *
     * <p><b>它不是通道，是钱包。</b>这是国内开发者最容易搞错的一点。
     *
     * <p>Apple Pay 不处理资金，只负责把用户的银行卡信息做<b>网络令牌化</b>（DPAN），
     * 产出一个一次性 payment token。这笔交易最终必须由某家 PSP 或收单行
     * （Stripe / Worldpay / Antom / Adyen 之一）去完成授权与请款。
     *
     * <p>因此在本模型中：
     * <ul>
     *   <li>{@code PaymentMethod.APPLE_PAY} 是「支付方式」，挂在 CARD 类通道下。</li>
     *   <li>{@code ChannelCode.APPLE_PAY} 的 category 是 WALLET，
     *       {@code isAcquirable()} 为 false，<b>会被路由自动排除</b>。</li>
     * </ul>
     * 保留这条配置正是为了演示：能力矩阵会明确拒绝「把 Apple Pay 当通道直接下单」。
     *
     * <p>另一个关键差异：Apple Pay 的 payment token 是<b>一次性的</b>，
     * 不能用于下次扣款。要做订阅续费，必须走网络令牌或 Vault 体系
     * （见 {@code PayerIdentity#isSingleUse()}）。
     */
    private ChannelCapability applePay() {
        return new ChannelCapability(
                ChannelCode.APPLE_PAY,
                true,
                Set.of(PaymentMethod.APPLE_PAY),
                Set.of(InteractionMode.FRONTEND_SDK),
                Set.of(
                        Capability.NETWORK_TOKENIZATION,
                        Capability.FRONTEND_SDK_INVOKE,
                        Capability.THREE_DS_CHALLENGE,
                        Capability.ASYM_KEY_SIGN),
                AmountConstraintHolder.multi(
                        Map.of(Currency.USD, List.of(0.01, 100000.0),
                                Currency.EUR, List.of(0.01, 90000.0),
                                Currency.GBP, List.of(0.01, 80000.0),
                                Currency.HKD, List.of(0.1, 780000.0),
                                Currency.SGD, List.of(0.01, 130000.0),
                                Currency.AUD, List.of(0.01, 150000.0),
                                Currency.JPY, List.of(1.0, 15000000.0),
                                Currency.CNY, List.of(0.01, 50000.0))),
                new RefundPolicy(false, 0, null, false, false, true, false),
                NotifySpecHolder.pullOnly(),
                new IdempotencySpec(IdempotencySpec.IdempotencyScope.REQUEST_HEADER,
                        Duration.ofHours(1), IdempotencySpec.ConflictBehaviour.UNDEFINED,
                        "Apple Pay 本身不做幂等，由下游 PSP 保证"),
                AuthModel.MERCHANT_CERT,
                new ChannelCapability.SettlementLatency(Duration.ofDays(2), false, "由下游收单行决定"),
                9);
    }

    // =====================================================================
    // 配置构造辅助
    // =====================================================================

    /** 金额与通知规范的构造辅助，避免主配置被样板代码淹没。 */
    static final class AmountConstraintHolder {

        private AmountConstraintHolder() {
        }

        static AmountConstraint cny(double min, double max) {
            return new AmountConstraint(Set.of(Currency.CNY),
                    Map.of(Currency.CNY, range(Currency.CNY, min, max)));
        }

        /**
         * 多币种限额。
         *
         * <p>必须按币种分别配置：不能拿人民币的 5 万上限去卡日元，
         * 那是相差两个数量级的数。日元本身还是零小数位币种，
         * 最小单位就是 1 元，绝不能按「分」处理。
         */
        static AmountConstraint multi(Map<Currency, List<Double>> limits) {
            Map<Currency, AmountConstraint.MoneyRange> ranges = new EnumMap<>(Currency.class);
            limits.forEach((currency, bound) ->
                    ranges.put(currency, range(currency, bound.get(0), bound.get(1))));
            return new AmountConstraint(ranges.keySet(), ranges);
        }

        static AmountConstraint.MoneyRange range(Currency currency, double min, double max) {
            return new AmountConstraint.MoneyRange(
                    Money.of(String.valueOf(min), currency),
                    Money.of(String.valueOf(max), currency));
        }
    }

    static final class NotifySpecHolder {

        private NotifySpecHolder() {
        }

        /** 推送型通知：签名、至少一次投递，可能乱序。 */
        static NotifySpec push(int maxRetries, Duration retryWindow, boolean outOfOrder) {
            return new NotifySpec(NotifySpec.NotifyMode.PUSH, true, true, outOfOrder,
                    Duration.ofSeconds(1), maxRetries, retryWindow);
        }

        /** 纯拉取型：没有推送，只能主动查单（银行转账等）。 */
        static NotifySpec pullOnly() {
            return new NotifySpec(NotifySpec.NotifyMode.PULL, false, false, false,
                    Duration.ZERO, 0, Duration.ZERO);
        }
    }
}
