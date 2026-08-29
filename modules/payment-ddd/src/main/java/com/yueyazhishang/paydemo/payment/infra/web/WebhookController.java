package com.yueyazhishang.paydemo.payment.infra.web;

import com.yueyazhishang.paydemo.payment.application.ProcessWebhookService;
import com.yueyazhishang.paydemo.payment.infra.adapter.ChannelAdapter;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/webhooks")
public class WebhookController {

    private final ChannelAdapter stripeAdapter;
    private final ChannelAdapter paypalAdapter;
    private final ProcessWebhookService processWebhookService;

    public WebhookController(ChannelAdapter stripeAdapter, ChannelAdapter paypalAdapter, ProcessWebhookService processWebhookService) {
        this.stripeAdapter = stripeAdapter;
        this.paypalAdapter = paypalAdapter;
        this.processWebhookService = processWebhookService;
    }

    @PostMapping("/stripe")
    public ResponseEntity<String> stripeWebhook(@RequestBody String payload, @RequestHeader(value = "Stripe-Signature", required = false) String sig) {
        String normalized = stripeAdapter.handleWebhook(payload, sig);
        if ("invalid".equals(normalized) || "error".equals(normalized)) {
            return ResponseEntity.status(400).body("invalid");
        }
        processWebhookService.processStripeWebhook(normalized);
        return ResponseEntity.ok("ok");
    }

    @PostMapping("/paypal")
    public ResponseEntity<String> paypalWebhook(@RequestBody String payload,
                                                @RequestHeader(value = "Paypal-Transmission-Id", required = false) String transmissionId,
                                                @RequestHeader(value = "Paypal-Transmission-Time", required = false) String transmissionTime,
                                                @RequestHeader(value = "Paypal-Cert-Url", required = false) String certUrl,
                                                @RequestHeader(value = "Paypal-Auth-Algo", required = false) String authAlgo,
                                                @RequestHeader(value = "Paypal-Transmission-Sig", required = false) String transmissionSig) {
        String composite = String.join("|", transmissionId == null ? "" : transmissionId,
                transmissionTime == null ? "" : transmissionTime,
                certUrl == null ? "" : certUrl,
                authAlgo == null ? "" : authAlgo,
                transmissionSig == null ? "" : transmissionSig);
        String normalized = paypalAdapter.handleWebhook(payload, composite);
        if ("invalid".equals(normalized) || "error".equals(normalized)) {
            return ResponseEntity.status(400).body("invalid");
        }
        // pass to application service
        processWebhookService.processPaypalWebhook(normalized);
        return ResponseEntity.ok("ok");
    }
}
