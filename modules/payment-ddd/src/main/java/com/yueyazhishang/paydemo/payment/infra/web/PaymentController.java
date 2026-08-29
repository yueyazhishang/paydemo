package com.yueyazhishang.paydemo.payment.infra.web;

import com.yueyazhishang.paydemo.payment.application.CreatePaymentCommand;
import com.yueyazhishang.paydemo.payment.application.CreatePaymentService;
import com.yueyazhishang.paydemo.payment.domain.Payment;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final CreatePaymentService createPaymentService;

    public PaymentController(CreatePaymentService createPaymentService) {
        this.createPaymentService = createPaymentService;
    }

    @PostMapping
    public ResponseEntity<Payment> create(@RequestBody CreatePaymentRequest req) {
        CreatePaymentCommand cmd = new CreatePaymentCommand(req.getOrderId(), req.getAmount(), req.getCurrency(), req.getPaymentMethod());
        Payment p = createPaymentService.create(cmd);
        return ResponseEntity.ok(p);
    }

    public static class CreatePaymentRequest {
        private String orderId;
        private BigDecimal amount;
        private String currency;
        private String paymentMethod;

        public String getOrderId() {
            return orderId;
        }

        public BigDecimal getAmount() {
            return amount;
        }

        public String getCurrency() {
            return currency;
        }

        public String getPaymentMethod() {
            return paymentMethod;
        }
    }
}
