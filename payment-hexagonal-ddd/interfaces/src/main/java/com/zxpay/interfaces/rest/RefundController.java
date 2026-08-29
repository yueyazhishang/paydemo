package com.zxpay.interfaces.rest;

import com.zxpay.application.dto.PaymentCommands;
import com.zxpay.application.refund.RefundApplicationService;
import com.zxpay.domain.merchant.model.MerchantAppId;
import com.zxpay.domain.payment.model.PaymentOrderId;
import com.zxpay.domain.refund.model.RefundOrderId;
import com.zxpay.sharedkernel.money.Currency;
import com.zxpay.sharedkernel.money.Money;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * 退款接口（入站适配器）。
 *
 * <p>只有两个写操作：发起退款、同步退款状态。
 * 退款的复杂性全在领域层（资格校验、窗口、次数、跨聚合预留），
 * Controller 只做参数转换。
 */
@RestController
@RequestMapping("/api/v1/refunds")
public class RefundController {

    private final RefundApplicationService refundService;

    public RefundController(RefundApplicationService refundService) {
        this.refundService = refundService;
    }

    /**
     * 发起退款。
     *
     * <p>支持部分退款：金额小于原额即为部分退款。
     * 是否允许部分退款由通道能力矩阵决定——
     * 不支持的通道会在资格校验阶段就被拒绝，
     * 而不是等打到通道才报错。
     */
    @PostMapping
    public ResponseEntity<PaymentCommands.RefundResult> create(
            @RequestBody CreateRefundRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        PaymentCommands.CreateRefundCommand command = new PaymentCommands.CreateRefundCommand(
                MerchantAppId.of(request.appId()),
                PaymentOrderId.of(request.paymentOrderId()),
                request.merchantRefundNo(),
                idempotencyKey,
                Money.of(request.amount(), Currency.of(request.currency())),
                request.reason(),
                request.metadata());

        return ResponseEntity.ok(refundService.createRefund(command));
    }

    /**
     * 主动同步退款状态。
     *
     * <p>卡退款要 5~10 个工作日才到账，退款通知也可能丢失。
     * 商户应定期调用此接口，而不是干等通知。
     */
    @PostMapping("/{refundOrderId}/sync")
    public ResponseEntity<PaymentCommands.RefundResult> sync(@PathVariable String refundOrderId) {
        return ResponseEntity.ok(refundService.syncRefund(RefundOrderId.of(refundOrderId)));
    }

    /** 触发一次退款补偿扫描。 */
    @PostMapping("/compensate")
    public ResponseEntity<Integer> compensate() {
        return ResponseEntity.ok(refundService.compensatePendingRefunds());
    }

    public record CreateRefundRequest(
            String appId,
            String paymentOrderId,
            String merchantRefundNo,
            BigDecimal amount,
            String currency,
            String reason,
            java.util.Map<String, String> metadata
    ) {
    }
}
