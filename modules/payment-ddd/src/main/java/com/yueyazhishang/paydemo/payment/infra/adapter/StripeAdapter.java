package com.yueyazhishang.paydemo.payment.infra.adapter;

import com.yueyazhishang.paydemo.payment.application.CreatePaymentCommand;
import com.stripe.Stripe;
import com.stripe.model.PaymentIntent;
import com.stripe.param.PaymentIntentCreateParams;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component("stripeAdapter")
public class StripeAdapter implements ChannelAdapter {

    public StripeAdapter(@Value("${stripe.apiKey:}") String apiKey) {
        if (apiKey != null && !apiKey.isEmpty()) {
            Stripe.apiKey = apiKey;
        }
    }

    @Override
    public CreateResult createPayment(CreatePaymentCommand command) {
        try {
            PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                    .setAmount(command.getAmount().multiply(java.math.BigDecimal.valueOf(100)).longValue())
                    .setCurrency(command.getCurrency().toLowerCase())
                    .setDescription("Order " + command.getOrderId())
                    .build();

            PaymentIntent intent = PaymentIntent.create(params);
            return new CreateResult(true, intent.getId(), intent.toJson());
        } catch (Exception e) {
            return new CreateResult(false, null, e.getMessage());
        }
    }

    @Override
    public String handleWebhook(String payload, String signatureHeader) {
        // For demo we return payload; in real impl verify signature using Stripe's webhook secret
        return payload;
    }
}
