package com.zxpay.domain.notify.model;

import com.zxpay.domain.merchant.model.MerchantAppId;
import com.zxpay.domain.payment.model.PaymentOrderId;
import com.zxpay.domain.refund.model.RefundOrderId;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;

/**
 * 商户通知任务：我们要回传给商户的那条结果。
 *
 * <p>为什么商户通知要单独建模成一个任务，而不是「在支付成功时同步调一次商户接口」：
 * <ol>
 *   <li><b>商户接口一定会挂</b>。商户服务器重启、发布、超时、返回 5xx 都是常态。
 *       同步调用失败就意味着商户永远收不到通知，订单卡死。</li>
 *   <li><b>必须可重放</b>。商户需要能手动重推某条通知来排查问题。</li>
 *   <li><b>需要完整的投递轨迹</b>。商户声称「没收到通知」时，
 *       我们必须能拿出「第 3 次投递返回了 500」的证据。</li>
 * </ol>
 *
 * <p>因此通知是一个<b>持久化任务 + 递增间隔重试</b>的机制，
 * 而不是一次 HTTP 调用。重试间隔通常取
 * 1m / 5m / 30m / 2h / 6h / 24h 这类递增序列。
 */
public record MerchantNotifyTask(
        MerchantAppId appId,

        /** 关联的支付单。退款通知时为空。 */
        PaymentOrderId paymentOrderId,

        /** 关联的退款单。支付通知时为空。 */
        RefundOrderId refundOrderId,

        String merchantOrderNo,

        /** 通知类型：payment.succeeded / payment.failed / refund.succeeded ... */
        String eventType,

        /** 投递给商户的业务内容。 */
        Map<String, String> payload,

        /** 商户回调地址。下单时快照，避免商户改配置后老单找不到地址。 */
        String notifyUrl,

        /** 第几次投递，从 1 开始。 */
        int attemptNo,

        /** 本次投递的计划时间。 */
        Instant scheduledAt
) {

    public MerchantNotifyTask {
        payload = payload == null ? Map.of() : Collections.unmodifiableMap(payload);
        if (attemptNo < 1) {
            throw new IllegalArgumentException("attemptNo must start from 1");
        }
    }

    public static final int MAX_ATTEMPTS = 8;

    public boolean exhausted() {
        return attemptNo >= MAX_ATTEMPTS;
    }

    /** 计算下次重试时间。递增间隔，避免持续冲击故障中的商户服务。 */
    public MerchantNotifyTask nextAttempt(Instant now) {
        long[] delaysMinutes = {1, 5, 30, 120, 360, 720, 1440};
        int idx = Math.min(attemptNo - 1, delaysMinutes.length - 1);
        return new MerchantNotifyTask(appId, paymentOrderId, refundOrderId, merchantOrderNo,
                eventType, payload, notifyUrl, attemptNo + 1,
                now.plusSeconds(delaysMinutes[idx] * 60));
    }
}
