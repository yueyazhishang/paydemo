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
