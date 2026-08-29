package com.zxpay.interfaces.rest;

import com.zxpay.application.dto.PaymentCommands;
import com.zxpay.application.payment.PaymentApplicationService;
import com.zxpay.domain.merchant.model.MerchantAppId;
import com.zxpay.domain.payment.model.PaymentOrderId;
import com.zxpay.domain.payment.model.PaymentScene;
import com.zxpay.domain.payment.model.TerminalType;
import com.zxpay.sharedkernel.money.Currency;
import com.zxpay.sharedkernel.money.Money;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * 商户支付接口（入站适配器）。
 *
 * <p>职责只有三件事：<b>协议转换、鉴权、转发</b>。
 * 这里不允许出现任何业务规则——
 * 一旦在 Controller 里写了 {@code if (status == X)}，
 * 就意味着领域逻辑泄漏，后续从 MQ、定时任务、管理台进来时
 * 都要把同样的判断再写一遍，最终必然不一致。
 *
 * <p>幂等键从 {@code Idempotency-Key} 请求头取，这是行业惯例。
 * 商户侧必须保证同一笔业务重试时带相同的键。
 */
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentApplicationService paymentService;

    public PaymentController(PaymentApplicationService paymentService) {
        this.paymentService = paymentService;
    }

    /** 创建支付（统一下单）。 */
    @PostMapping
    public ResponseEntity<PaymentCommands.PaymentResult> create(
            @RequestBody CreatePaymentRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        Money amount = Money.of(request.amount(), Currency.of(request.currency()));

        PaymentCommands.CreatePaymentCommand command = new PaymentCommands.CreatePaymentCommand(
                MerchantAppId.of(request.appId()),
                request.merchantOrderNo(),
                idempotencyKey,
                amount,
                request.paymentMethod(),
                request.interactionMode(),
                PaymentScene.of(
                        request.terminal() != null ? request.terminal() : TerminalType.API,
                        request.clientIp(),
                        request.countryCode()),
                null,
                request.subject(),
                request.captureMode(),
                null,
                request.notifyUrl(),
                request.returnUrl(),
                request.metadata());

        return ResponseEntity.ok(paymentService.createPayment(command));
    }

    /** 主动查单。商户可用它在未收到通知时主动同步状态。 */
    @PostMapping("/{paymentOrderId}/sync")
    public ResponseEntity<PaymentCommands.PaymentResult> sync(@PathVariable String paymentOrderId) {
        return ResponseEntity.ok(paymentService.queryAndSync(PaymentOrderId.of(paymentOrderId)));
    }

    /** 重试下发通道。用于首次下发失败后的手动重试（复用同一幂等键，安全）。 */
    @PostMapping("/{paymentOrderId}/submit")
    public ResponseEntity<PaymentCommands.PaymentResult> submit(@PathVariable String paymentOrderId) {
        return ResponseEntity.ok(paymentService.submitToChannel(PaymentOrderId.of(paymentOrderId)));
    }

    /** 关闭订单。已支付的订单会被领域层拒绝——要终止必须走退款。 */
    @PostMapping("/{paymentOrderId}/close")
    public ResponseEntity<PaymentCommands.PaymentResult> close(
            @PathVariable String paymentOrderId,
            @RequestBody(required = false) CloseRequest request) {
        String reason = request == null || request.reason() == null ? "MERCHANT_CLOSED" : request.reason();
        return ResponseEntity.ok(paymentService.closePayment(PaymentOrderId.of(paymentOrderId), reason));
    }

    /**
     * 请款。仅海外 auth 模式的订单需要。
     *
     * <p>对国内通道调用会返回明确错误「该通道不支持请款」，
     * 这个判断来自能力矩阵，不是硬编码的分支。
     */
    @PostMapping("/{paymentOrderId}/capture")
    public ResponseEntity<PaymentCommands.PaymentResult> capture(
            @PathVariable String paymentOrderId,
            @RequestBody CaptureRequest request) {
        Money amount = request.amount() == null ? null : Money.of(request.amount(), Currency.of(request.currency()));
        return ResponseEntity.ok(paymentService.capture(PaymentOrderId.of(paymentOrderId), amount));
    }

    /** 触发一次补偿扫描。生产环境应由定时任务调用，这里暴露出来便于演示。 */
    @PostMapping("/compensate")
    public ResponseEntity<Integer> compensate() {
        return ResponseEntity.ok(paymentService.compensatePendingPayments());
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }

    // =====================================================================
    // 请求体
    // =====================================================================

    public record CreatePaymentRequest(
            String appId,
            String merchantOrderNo,
            com.zxpay.domain.channel.model.PaymentMethod paymentMethod,
            com.zxpay.domain.channel.model.InteractionMode interactionMode,
            BigDecimal amount,
            String currency,
            TerminalType terminal,
            String clientIp,
            String countryCode,
            String subject,
            com.zxpay.domain.payment.model.CaptureMode captureMode,
            String notifyUrl,
            String returnUrl,
            java.util.Map<String, String> metadata
    ) {
    }

    public record CloseRequest(String reason) {
    }

    public record CaptureRequest(BigDecimal amount, String currency) {
    }
}
