package com.example.payment.interfaces.rest;

import com.example.payment.application.command.CreatePaymentCommand;
import com.example.payment.application.dto.PaymentOrderDTO;
import com.example.payment.application.service.PaymentAppService;
import com.example.payment.infrastructure.config.PayProperties;
import com.example.payment.shared.ApiResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 支付接口（Open Host Service）：
 * 对上游业务系统暴露的稳定契约，内部领域模型不外泄（DTO 为 Published Language）。
 */
@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentAppService paymentAppService;
    private final PayProperties payProperties;

    /** 收单：创建支付单并返回收银台要素 */
    @PostMapping
    public ApiResult<PaymentOrderDTO> createPayment(@Valid @RequestBody CreatePaymentCommand command) {
        String notifyUrl = payProperties.getNotifyBaseUrl() + "/api/notify/" + command.getChannel();
        return ApiResult.ok(paymentAppService.createPayment(command, notifyUrl));
    }

    /** 查询支付单 */
    @GetMapping("/{paymentId}")
    public ApiResult<PaymentOrderDTO> getPayment(@PathVariable String paymentId) {
        return ApiResult.ok(paymentAppService.getPayment(paymentId));
    }

    /** 查单兜底：主动向渠道查询并同步本地状态 */
    @PostMapping("/{paymentId}/query")
    public ApiResult<PaymentOrderDTO> queryAndSync(@PathVariable String paymentId) {
        return ApiResult.ok(paymentAppService.queryAndSyncPayment(paymentId));
    }

    /** 关单 */
    @PostMapping("/{paymentId}/close")
    public ApiResult<PaymentOrderDTO> close(@PathVariable String paymentId) {
        return ApiResult.ok(paymentAppService.closePayment(paymentId));
    }
}
