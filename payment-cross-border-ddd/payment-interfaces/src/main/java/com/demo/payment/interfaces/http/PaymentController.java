package com.demo.payment.interfaces.http;

import com.demo.payment.application.command.CreatePaymentCommand;
import com.demo.payment.application.command.PaymentCommandService;
import com.demo.payment.application.command.PayResult;
import com.demo.payment.application.command.RefundCommandService;
import com.demo.payment.domain.channel.model.PaymentMethodType;
import com.demo.payment.shared.money.Currency;
import com.demo.payment.shared.money.Money;
import com.demo.payment.shared.util.IdGenerator;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * 支付接入层（REST API）。
 *
 * <p><b>接入层的职责边界：只做三件事</b>
 * <ol>
 *   <li>协议转换：HTTP ↔ 应用层命令</li>
 *   <li>参数校验：格式、必填、范围</li>
 *   <li>安全：签名验证、限流、幂等键提取</li>
 * </ol>
 *
 * <p><b>绝不能在这里写业务逻辑。</b>
 * 常见错误是在 Controller 里判断"订单能不能退"——
 * 那样一来逻辑无法复用（定时任务、MQ 消费者要走另一条路），
 * 二来无法测试（必须起 Spring 容器）。
 */
@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentCommandService paymentCommandService;
    private final RefundCommandService refundCommandService;

    public PaymentController(PaymentCommandService paymentCommandService,
                             RefundCommandService refundCommandService) {
        this.paymentCommandService = paymentCommandService;
        this.refundCommandService = refundCommandService;
    }

    /**
     * 发起支付。
     *
     * <p><b>幂等键来源</b>：优先取客户端的 {@code Idempotency-Key} 请求头。
     * 客户端未传时，服务端用 {@code merchantId + merchantOrderNo} 兜底生成 ——
     * 这样即使客户端不做幂等，重复提交同一笔单也不会产生第二笔支付。
     */
    @PostMapping
    public ResponseEntity<?> pay(@RequestBody Map<String, String> request,
                                 @RequestHeader(value = "Idempotency-Key", required = false)
                                 String idempotencyKey) {
        String merchantId = request.get("merchantId");
        String merchantOrderNo = request.get("merchantOrderNo");
        String currencyCode = request.get("currency");
        BigDecimal amount = new BigDecimal(request.get("amount"));

        Currency currency = Currency.require(currencyCode);

        // 客户端未传幂等键时，用商户订单号兜底 —— 保证业务层幂等
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            idempotencyKey = "AUTO_" + merchantId + "_" + merchantOrderNo;
        }

        CreatePaymentCommand cmd = new CreatePaymentCommand(
                merchantId,
                merchantOrderNo,
                Money.ofMajor(amount, currency),
                PaymentMethodType.valueOf(request.get("paymentMethod")),
                request.get("subject"),
                request.get("notifyUrl"),
                request.get("returnUrl"),
                request.get("clientIp"),
                request.get("payerId"),
                request.get("paymentCredential"),
                idempotencyKey,
                request.getOrDefault("countryCode", "CN"),
                request.getOrDefault("scene", "APP"),
                Instant.now().plus(30, ChronoUnit.MINUTES)
        );

        PayResult result = paymentCommandService.pay(cmd);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(result);
    }

    /** 退款 */
    @PostMapping("/refund")
    public ResponseEntity<?> refund(@RequestBody Map<String, String> request) {
        String merchantId = request.get("merchantId");
        String merchantOrderNo = request.get("merchantOrderNo");
        Money amount = Money.ofMajor(new BigDecimal(request.get("amount")),
                Currency.require(request.get("currency")));

        var refund = refundCommandService.refund(merchantId, merchantOrderNo,
                amount, request.get("reason"));
        return ResponseEntity.ok(Map.of(
                "refundNo", refund.refundNo(),
                "amount", refund.amount().toString(),
                "status", refund.status().name()
        ));
    }
}
