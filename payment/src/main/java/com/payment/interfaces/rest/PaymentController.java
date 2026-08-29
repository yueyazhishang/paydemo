package com.payment.interfaces.rest;

import com.payment.application.payment.PaymentAppService;
import com.payment.application.payment.dto.CreatePaymentRequest;
import com.payment.application.payment.dto.CreatePaymentResponse;
import com.payment.application.payment.dto.PaymentQueryResponse;
import com.payment.application.payment.dto.RefundRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 支付REST控制器
 * 
 * 负责:
 * 1. 接收HTTP请求
 * 2. 参数验证
 * 3. 调用应用服务
 * 4. 返回响应
 */
@Slf4j
@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {
    
    private final PaymentAppService paymentAppService;
    
    /**
     * 创建支付订单
     */
    @PostMapping
    public ResponseEntity<CreatePaymentResponse> createPayment(
            @Valid @RequestBody CreatePaymentRequest request) {
        log.info("收到创建支付请求, merchantId={}, orderId={}, channelCode={}", 
                request.getMerchantId(), request.getOrderId(), request.getChannelCode());
        
        CreatePaymentResponse response = paymentAppService.createPayment(request);
        return ResponseEntity.ok(response);
    }
    
    /**
     * 查询支付订单
     */
    @GetMapping("/{paymentId}")
    public ResponseEntity<PaymentQueryResponse> queryPayment(@PathVariable String paymentId) {
        log.info("查询支付订单, paymentId={}", paymentId);
        
        PaymentQueryResponse response = paymentAppService.queryPayment(paymentId);
        return ResponseEntity.ok(response);
    }
    
    /**
     * 同步支付状态
     */
    @PostMapping("/{paymentId}/sync")
    public ResponseEntity<PaymentQueryResponse> syncPaymentStatus(@PathVariable String paymentId) {
        log.info("同步支付状态, paymentId={}", paymentId);
        
        PaymentQueryResponse response = paymentAppService.syncPaymentStatus(paymentId);
        return ResponseEntity.ok(response);
    }
    
    /**
     * 关闭支付订单
     */
    @PostMapping("/{paymentId}/close")
    public ResponseEntity<Boolean> closePayment(@PathVariable String paymentId) {
        log.info("关闭支付订单, paymentId={}", paymentId);
        
        boolean result = paymentAppService.closePayment(paymentId);
        return ResponseEntity.ok(result);
    }
    
    /**
     * 发起退款
     */
    @PostMapping("/{paymentId}/refunds")
    public ResponseEntity<RefundRequest> createRefund(
            @PathVariable String paymentId,
            @Valid @RequestBody RefundRequest request) {
        log.info("发起退款, paymentId={}, amount={}", paymentId, request.getRefundAmount());
        
        request.setPaymentId(paymentId);
        RefundRequest response = paymentAppService.createRefund(request);
        return ResponseEntity.ok(response);
    }
}
