package com.demo.payment.adapter.applepay;

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
