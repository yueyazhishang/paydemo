package com.demo.payment.adapter.jdpay;

import com.demo.payment.adapter.core.AbstractChannelAdapter;
import com.demo.payment.domain.acquiring.model.vo.OutTradeNo;
import com.demo.payment.domain.channel.model.*;
import com.demo.payment.domain.channel.spi.*;
import com.demo.payment.shared.money.Currency;
import com.demo.payment.shared.money.Money;

import java.time.Instant;
import java.util.Set;

/**
 * 京东支付适配器。
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
 * </ul>
 */
public class JdPayAdapter extends AbstractChannelAdapter {

    private static final ChannelCapability CAPABILITY = new ChannelCapability(
            ChannelCode.JD_PAY,
            "京东支付",
            ChannelCapability.AcquiringModel.GATEWAY,
            Set.of(PaymentMethodType.JD_PAY, PaymentMethodType.BANK_CARD),
            false,
            false,
            true,
            false,
            365,
            false,
            ChannelCapability.NotifyMode.PUSH_AND_PULL,
            ChannelCapability.IdempotencyMode.MERCHANT_ORDER_NO_ONLY,
            ChannelCapability.SignatureAlgorithm.HMAC_SHA256,
            false,
            Set.of(ChannelCapability.IntegrationMode.REDIRECT_CHECKOUT, ChannelCapability.IntegrationMode.NATIVE_SDK),
            Set.of(Currency.CNY),
            1L,
            20000000L,
            java.time.Duration.ofMinutes(30),
            true,
            ChannelCapability.SettlementMode.DEFERRED
    );

    @Override
    public ChannelCode channelCode() {
        return ChannelCode.JD_PAY;
    }

    @Override
    public ChannelCapability capability() {
        return CAPABILITY;
    }

    @Override
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
    }


}
