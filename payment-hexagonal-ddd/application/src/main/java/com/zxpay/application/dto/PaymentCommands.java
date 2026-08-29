package com.zxpay.application.dto;

import com.zxpay.domain.channel.model.ChannelCode;
import com.zxpay.domain.channel.model.InteractionMode;
import com.zxpay.domain.channel.model.PaymentMethod;
import com.zxpay.domain.merchant.model.MerchantAppId;
import com.zxpay.domain.payment.model.CaptureMode;
import com.zxpay.domain.payment.model.ChannelInteraction;
import com.zxpay.domain.payment.model.ChannelResultApplication;
import com.zxpay.domain.payment.model.PaymentOrderId;
import com.zxpay.domain.payment.model.PaymentScene;
import com.zxpay.domain.payment.model.PaymentStatus;
import com.zxpay.domain.payment.model.PayerIdentity;
import com.zxpay.domain.refund.model.RefundOrderId;
import com.zxpay.domain.refund.model.RefundStatus;
import com.zxpay.sharedkernel.money.Money;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * 应用层的入参与出参。
 *
 * <p>为什么单独定义 DTO 而不直接把领域对象传出去：
 * <ol>
 *   <li><b>隔离变更</b>。领域模型的演进不应影响对外契约。
 *       领域层加了字段、改了方法名，不应该导致商户接口需要升版本。</li>
 *   <li><b>控制暴露</b>。领域对象里有大量内部状态（乐观锁版本、尝试列表、
 *       领域事件），这些不该出现在 API 响应里。</li>
 *   <li><b>协议适配</b>。入站适配器（REST / RPC / MQ）各自把这些 DTO
 *       转成自己的协议格式，应用层不感知 HTTP。</li>
 * </ol>
 */
public final class PaymentCommands {

    private PaymentCommands() {
    }

    // =====================================================================
    // 支付
    // =====================================================================

    /** 创建支付。 */
    public record CreatePaymentCommand(
            MerchantAppId appId,

            /** 商户订单号。同一应用内必须唯一，是我们做业务幂等的依据。 */
            String merchantOrderNo,

            /** 接口幂等键。商户侧生成，带在请求头 Idempotency-Key 里。 */
            String idempotencyKey,

            Money amount,
            PaymentMethod paymentMethod,

            /** 期望的交互形态。不传则按支付方式默认值。 */
            InteractionMode interactionMode,

            PaymentScene scene,
            PayerIdentity payerIdentity,
            String subject,
            CaptureMode captureMode,
            Duration expiry,
            String notifyUrl,
            String returnUrl,
            Map<String, String> metadata
    ) {

        public CreatePaymentCommand {
            metadata = metadata == null ? Map.of() : metadata;
        }

        public static CreatePaymentCommand simple(MerchantAppId appId, String merchantOrderNo,
                                                  String idempotencyKey, Money amount,
                                                  PaymentMethod method, PaymentScene scene) {
            return new CreatePaymentCommand(appId, merchantOrderNo, idempotencyKey, amount, method,
                    null, scene, null, null, CaptureMode.AUTOMATIC, null, null, null, null);
        }
    }

    /** 支付结果。 */
    public record PaymentResult(
            PaymentOrderId paymentOrderId,
            String merchantOrderNo,
            PaymentStatus status,
            ChannelCode channel,

            /** 前端唤起参数。API_ONLY 模式下为空，表示同步出终态。 */
            ChannelInteraction interaction,

            /** 通道原始状态，保留供排查。 */
            String channelRawStatus,

            String failureCode,
            String failureMessage,
            Instant expireAt,
            Instant createdAt
    ) {

        public static PaymentResult of(PaymentOrderId id, String merchantOrderNo, PaymentStatus status,
                                       ChannelCode channel, ChannelInteraction interaction,
                                       String rawStatus, String failureCode, String failureMessage,
                                       Instant expireAt, Instant createdAt) {
            return new PaymentResult(id, merchantOrderNo, status, channel, interaction,
                    rawStatus, failureCode, failureMessage, expireAt, createdAt);
        }
    }

    /** 回调处理结果。 */
    public record NotifyHandleResult(
            boolean accepted,

            /** 是否已验签通过。未通过一律拒绝处理，且必须告警。 */
            boolean signatureValid,

            ChannelResultApplication application,

            String message
    ) {

        public static NotifyHandleResult rejected(String message) {
            return new NotifyHandleResult(false, false, null, message);
        }

        public static NotifyHandleResult accepted(ChannelResultApplication application, String message) {
            return new NotifyHandleResult(true, true, application, message);
        }
    }

    // =====================================================================
    // 退款
    // =====================================================================

    /** 创建退款。 */
    public record CreateRefundCommand(
            MerchantAppId appId,
            PaymentOrderId paymentOrderId,

            /** 商户退款单号。同一应用内唯一，退款业务幂等的依据。 */
            String merchantRefundNo,

            /** 接口幂等键。 */
            String idempotencyKey,

            Money amount,
            String reason,
            Map<String, String> metadata
    ) {

        public CreateRefundCommand {
            metadata = metadata == null ? Map.of() : metadata;
        }
    }

    /** 退款结果。 */
    public record RefundResult(
            RefundOrderId refundOrderId,
            PaymentOrderId paymentOrderId,
            String merchantRefundNo,
            RefundStatus status,
            ChannelCode channel,
            Money amount,
            String failureCode,
            String failureMessage,
            Instant createdAt
    ) {

        public static RefundResult of(RefundOrderId refundId, PaymentOrderId paymentOrderId,
                                      String merchantRefundNo, RefundStatus status, ChannelCode channel,
                                      Money amount, String failureCode, String failureMessage, Instant createdAt) {
            return new RefundResult(refundId, paymentOrderId, merchantRefundNo, status, channel,
                    amount, failureCode, failureMessage, createdAt);
        }
    }
}
