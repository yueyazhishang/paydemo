package com.yueyazhishang.paydemo.payment.infra.web;

import com.yueyazhishang.paydemo.payment.infra.adapter.ChannelAdapter;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/webhooks")
public class WebhookController {

    private final ChannelAdapter stripeAdapter;
    private final ChannelAdapter paypalAdapter;

    public WebhookController(ChannelAdapter stripeAdapter, ChannelAdapter paypalAdapter) {
        this.stripeAdapter = stripeAdapter;
        this.paypalAdapter = paypalAdapter;
    }

    @PostMapping("/stripe")
    public ResponseEntity<String> stripeWebhook(@RequestBody String payload, @RequestHeader(value = "Stripe-Signature", required = false) String sig) {
        String normalized = stripeAdapter.handleWebhook(payload, sig);
        // In a full implementation we'd convert normalized -> domain command and dispatch
        return ResponseEntity.ok("ok");
    }

    @PostMapping("/paypal")
    public ResponseEntity<String> paypalWebhook(@RequestBody String payload, @RequestHeader(value = "Paypal-Transmission-Sig", required = false) String sig) {
        String normalized = paypalAdapter.handleWebhook(payload, sig);
        return ResponseEntity.ok("ok");
    }
}
