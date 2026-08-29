package com.yueyazhishang.paydemo.payment.infra.adapter;

import com.yueyazhishang.paydemo.payment.application.CreatePaymentCommand;
import org.springframework.stereotype.Component;

@Component("paypalAdapter")
public class PaypalAdapter implements ChannelAdapter {

    public PaypalAdapter() {
        // For demo: sandbox credentials loaded from config when implementing real integration
    }

    @Override
    public CreateResult createPayment(CreatePaymentCommand command) {
        // Stub: return a fake external id. Replace with PayPal Orders API call in real integration.
        String fakeId = "PAYPAL_FAKE_" + command.getOrderId();
        return new CreateResult(true, fakeId, "{\"stub\":true}\n");
    }

    @Override
    public String handleWebhook(String payload, String signatureHeader) {
        // Parse PayPal webhook payload, verify signature in real impl
        return payload;
    }
}
