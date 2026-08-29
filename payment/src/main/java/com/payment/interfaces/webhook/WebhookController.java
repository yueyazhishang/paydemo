package com.payment.interfaces.webhook;

import com.payment.domain.payment.model.aggregate.PaymentOrder;
import com.payment.domain.payment.model.enums.PaymentStatus;
import com.payment.domain.payment.model.valueobject.PaymentId;
import com.payment.domain.payment.repository.PaymentOrderRepository;
import com.payment.infrastructure.channel.ChannelAdapterRegistry;
import com.payment.infrastructure.channel.adapter.PaymentChannelAdapter;
import com.payment.infrastructure.channel.adapter.PaymentChannelAdapter.WebhookRequest;
import com.payment.infrastructure.channel.adapter.PaymentChannelAdapter.WebhookResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

/**
 * Webhook回调控制器
 * 
 * 处理各支付通道的异步通知
 * 
 * 安全考虑:
 * 1. 验证签名
 * 2. 幂等处理
 * 3. IP白名单(可选)
 */
@Slf4j
@RestController
@RequestMapping("/api/webhook")
@RequiredArgsConstructor
public class WebhookController {
    
    private final ChannelAdapterRegistry channelAdapterRegistry;
    private final PaymentOrderRepository paymentOrderRepository;
    
    /**
     * 微信支付通知
     */
    @PostMapping("/wechat")
    public ResponseEntity<String> handleWechatWebhook(
            @RequestBody String body,
            @RequestHeader Map<String, String> headers,
            @RequestHeader(value = "Wechatpay-Signature", required = false) String signature) {
        log.info("收到微信支付通知");
        
        try {
            // 获取微信适配器
            PaymentChannelAdapter adapter = channelAdapterRegistry.getAdapter("WECHAT_JSAPI");
            
            // 验证签名
            WebhookRequest request = new WebhookRequest(body, headers, signature);
            if (!adapter.verifyWebhook(request)) {
                log.warn("微信通知签名验证失败");
                return ResponseEntity.ok(adapter.getFailureResponse());
            }
            
            // 解析通知
            WebhookResult result = adapter.parseWebhook(request);
            
            // 处理通知
            processPaymentNotification("WECHAT_JSAPI", result);
            
            return ResponseEntity.ok(adapter.getSuccessResponse());
            
        } catch (Exception e) {
            log.error("处理微信通知异常", e);
            return ResponseEntity.ok("<xml><return_code><![CDATA[FAIL]]></return_code></xml>");
        }
    }
    
    /**
     * 支付宝通知
     */
    @PostMapping("/alipay")
    public ResponseEntity<String> handleAlipayWebhook(
            @RequestParam Map<String, String> params,
            @RequestHeader Map<String, String> headers) {
        log.info("收到支付宝通知, params={}", params);
        
        try {
            PaymentChannelAdapter adapter = channelAdapterRegistry.getAdapter("ALIPAY_PC");
            
            // 验证签名
            String body = params.toString();
            String signature = params.get("sign");
            WebhookRequest request = new WebhookRequest(body, headers, signature);
            
            if (!adapter.verifyWebhook(request)) {
                log.warn("支付宝通知签名验证失败");
                return ResponseEntity.ok(adapter.getFailureResponse());
            }
            
            // 处理通知
            String tradeStatus = params.get("trade_status");
            String outTradeNo = params.get("out_trade_no");
            String tradeNo = params.get("trade_no");
            
            if ("TRADE_SUCCESS".equals(tradeStatus) || "TRADE_FINISHED".equals(tradeStatus)) {
                processPaymentSuccess("ALIPAY_PC", tradeNo, outTradeNo);
            }
            
            return ResponseEntity.ok(adapter.getSuccessResponse());
            
        } catch (Exception e) {
            log.error("处理支付宝通知异常", e);
            return ResponseEntity.ok("failure");
        }
    }
    
    /**
     * Stripe Webhook
     */
    @PostMapping("/stripe")
    public ResponseEntity<String> handleStripeWebhook(
            @RequestBody String body,
            @RequestHeader(value = "Stripe-Signature", required = false) String signature) {
        log.info("收到Stripe通知");
        
        try {
            PaymentChannelAdapter adapter = channelAdapterRegistry.getAdapter("STRIPE");
            
            WebhookRequest request = new WebhookRequest(body, Map.of("stripe-signature", signature), signature);
            
            if (!adapter.verifyWebhook(request)) {
                return ResponseEntity.status(400).body("Invalid signature");
            }
            
            WebhookResult result = adapter.parseWebhook(request);
            processPaymentNotification("STRIPE", result);
            
            return ResponseEntity.ok("{}");
            
        } catch (Exception e) {
            log.error("处理Stripe通知异常", e);
            return ResponseEntity.status(400).body("Error");
        }
    }
    
    /**
     * PayPal Webhook
     */
    @PostMapping("/paypal")
    public ResponseEntity<String> handlePayPalWebhook(
            @RequestBody String body,
            @RequestHeader Map<String, String> headers) {
        log.info("收到PayPal通知");
        
        try {
            PaymentChannelAdapter adapter = channelAdapterRegistry.getAdapter("PAYPAL");
            
            String authAlgo = headers.get("paypal-auth-algo");
            WebhookRequest request = new WebhookRequest(body, headers, authAlgo);
            
            if (!adapter.verifyWebhook(request)) {
                return ResponseEntity.status(400).body("Invalid signature");
            }
            
            WebhookResult result = adapter.parseWebhook(request);
            processPaymentNotification("PAYPAL", result);
            
            return ResponseEntity.ok("{}");
            
        } catch (Exception e) {
            log.error("处理PayPal通知异常", e);
            return ResponseEntity.status(400).body("Error");
        }
    }
    
    /**
     * Adyen通知
     */
    @PostMapping("/adyen")
    public ResponseEntity<String> handleAdyenWebhook(@RequestBody String body) {
        log.info("收到Adyen通知");
        
        try {
            PaymentChannelAdapter adapter = channelAdapterRegistry.getAdapter("ADYEN");
            
            WebhookRequest request = new WebhookRequest(body, Map.of(), null);
            WebhookResult result = adapter.parseWebhook(request);
            
            processPaymentNotification("ADYEN", result);
            
            return ResponseEntity.ok("[accepted]");
            
        } catch (Exception e) {
            log.error("处理Adyen通知异常", e);
            return ResponseEntity.ok("[failed]");
        }
    }
    
    /**
     * Worldpay通知
     */
    @PostMapping("/worldpay")
    public ResponseEntity<String> handleWorldpayWebhook(@RequestBody String body) {
        log.info("收到Worldpay通知");
        
        try {
            PaymentChannelAdapter adapter = channelAdapterRegistry.getAdapter("WORLDPAY");
            
            WebhookRequest request = new WebhookRequest(body, Map.of(), null);
            WebhookResult result = adapter.parseWebhook(request);
            
            processPaymentNotification("WORLDPAY", result);
            
            return ResponseEntity.ok("{}");
            
        } catch (Exception e) {
            log.error("处理Worldpay通知异常", e);
            return ResponseEntity.status(400).body("Error");
        }
    }
    
    // ========== 私有方法 ==========
    
    /**
     * 处理支付通知
     */
    private void processPaymentNotification(String channelCode, WebhookResult result) {
        String channelOrderId = result.getChannelOrderId();
        if (channelOrderId == null) {
            log.warn("通知中无通道订单号");
            return;
        }
        
        // 查找对应支付订单
        Optional<PaymentOrder> orderOpt = paymentOrderRepository.findByChannelOrderId(channelCode, channelOrderId);
        if (orderOpt.isEmpty()) {
            log.warn("未找到对应的支付订单, channelOrderId={}", channelOrderId);
            return;
        }
        
        PaymentOrder order = orderOpt.get();
        
        // 幂等处理: 如果已经是成功状态，不再处理
        if (order.getStatus() == PaymentStatus.SUCCESS) {
            log.info("订单已处理，跳过, paymentId={}", order.getPaymentId().getValue());
            return;
        }
        
        // 更新状态
        if ("SUCCESS".equals(result.getStatus()) || "succeeded".equals(result.getStatus()) || 
            "Authorised".equals(result.getStatus())) {
            order.getCurrentTransaction().ifPresent(tx -> 
                order.processPaymentSuccess(channelOrderId, tx)
            );
            paymentOrderRepository.save(order);
            log.info("支付订单更新为成功, paymentId={}", order.getPaymentId().getValue());
        }
    }
    
    /**
     * 处理支付成功
     */
    private void processPaymentSuccess(String channelCode, String channelOrderId, String paymentId) {
        Optional<PaymentOrder> orderOpt = paymentOrderRepository.findById(PaymentId.of(paymentId));
        if (orderOpt.isPresent()) {
            PaymentOrder order = orderOpt.get();
            if (order.getStatus() != PaymentStatus.SUCCESS) {
                order.getCurrentTransaction().ifPresent(tx -> 
                    order.processPaymentSuccess(channelOrderId, tx)
                );
                paymentOrderRepository.save(order);
            }
        }
    }
}
