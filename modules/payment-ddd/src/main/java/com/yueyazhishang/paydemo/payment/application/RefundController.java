package com.yueyazhishang.paydemo.payment.application;

import com.yueyazhishang.paydemo.payment.domain.PaymentRepository;
import com.yueyazhishang.paydemo.payment.domain.RefundRepository;
import com.yueyazhishang.paydemo.payment.domain.Refund;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/refunds")
public class RefundController {
    private final RefundService refundService;

    public RefundController(RefundService refundService) {
        this.refundService = refundService;
    }

    @PostMapping
    public ResponseEntity<Refund> create(@RequestBody CreateRefundRequest req) {
        Refund r = refundService.initiateRefund(req.getPaymentId(), req.getAmount());
        return ResponseEntity.ok(r);
    }

    public static class CreateRefundRequest {
        private Long paymentId;
        private BigDecimal amount;
        public Long getPaymentId() { return paymentId; }
        public BigDecimal getAmount() { return amount; }
    }
}
