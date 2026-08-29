package com.yueyazhishang.paydemo.payment.infra.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.net.Webhook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component("stripeAdapter")
public class StripeAdapter implements ChannelAdapter {

    private final ObjectMapper mapper = new ObjectMapper();
    private final String webhookSecret;

    public StripeAdapter(@Value("${stripe.apiKey:}") String apiKey,
                         @Value("${stripe.webhookSecret:}") String webhookSecret) {
        if (apiKey != null && !apiKey.isEmpty()) {
            com.stripe.Stripe.apiKey = apiKey;
        }
        this.webhookSecret = webhookSecret;
    }

    @Override
    public CreateResult createPayment(com.yueyazhishang.paydemo.payment.application.CreatePaymentCommand command) {
        try {
            com.stripe.param.PaymentIntentCreateParams params = com.stripe.param.PaymentIntentCreateParams.builder()
                    .setAmount(command.getAmount().multiply(java.math.BigDecimal.valueOf(100)).longValue())
                    .setCurrency(command.getCurrency().toLowerCase())
                    .setDescription("Order " + command.getOrderId())
                    .build();

            com.stripe.model.PaymentIntent intent = com.stripe.model.PaymentIntent.create(params);
            return new CreateResult(true, intent.getId(), intent.toJson());
        } catch (Exception e) {
            return new CreateResult(false, null, e.getMessage());
        }
    }

    @Override
    public String handleWebhook(String payload, String signatureHeader) {
        try {
            // If webhookSecret configured, verify signature via Stripe SDK
            Event event = null;
            if (webhookSecret != null && !webhookSecret.isEmpty() && signatureHeader != null) {
                try {
                    event = Webhook.constructEvent(payload, signatureHeader, webhookSecret);
                } catch (SignatureVerificationException e) {
                    return "invalid";
                }
            } else {
                // parse payload directly (no verification)
                JsonNode node = mapper.readTree(payload);
                String type = node.has("type") ? node.get("type").asText() : null;
                String id = null;
                if (node.has("data") && node.get("data").has("object")) {
                    JsonNode obj = node.get("data").get("object");
                    if (obj.has("id")) id = obj.get("id").asText();
                    else if (obj.has("payment_intent")) id = obj.get("payment_intent").asText();
                }
                return mapper.createObjectNode().put("type", type == null ? "unknown" : type).put("id", id == null ? "" : id).toString();
            }

            if (event != null) {
                String type = event.getType();
                String id = null;
                JsonNode node = mapper.readTree(event.toJson());
                if (node.has("data") && node.get("data").has("object")) {
                    JsonNode obj = node.get("data").get("object");
                    if (obj.has("id")) id = obj.get("id").asText();
                    else if (obj.has("payment_intent")) id = obj.get("payment_intent").asText();
                }
                return mapper.createObjectNode().put("type", type).put("id", id == null ? "" : id).toString();
            }
        } catch (Exception e) {
            return "error";
        }
        return "error";
    }
}
