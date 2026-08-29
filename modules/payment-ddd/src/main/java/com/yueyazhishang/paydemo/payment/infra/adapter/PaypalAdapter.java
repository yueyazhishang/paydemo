package com.yueyazhishang.paydemo.payment.infra.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yueyazhishang.paydemo.payment.application.CreatePaymentCommand;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component("paypalAdapter")
public class PaypalAdapter implements ChannelAdapter {

    private final PaypalClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String webhookId;

    public PaypalAdapter(PaypalClient client, @Value("${paypal.webhookId:}") String webhookId) {
        this.client = client;
        this.webhookId = webhookId;
    }

    @Override
    public CreateResult createPayment(CreatePaymentCommand command) {
        try {
            long amountInCents = command.getAmount().multiply(java.math.BigDecimal.valueOf(100)).longValue();
            Map<String, Object> res = client.createAndCaptureOrder(command.getOrderId(), amountInCents, command.getCurrency());
            String captureId = (String) res.get("captureId");
            String raw = (String) res.get("raw");
            return new CreateResult(true, captureId, raw);
        } catch (Exception e) {
            return new CreateResult(false, null, e.getMessage());
        }
    }

    @Override
    public String handleWebhook(String payload, String signatureHeader) {
        try {
            // For PayPal we expect several headers. In controller we pass transmission headers via signatureHeader as a composite (demo)
            // In production, pass each header separately. For demo we try minimal verification.
            // signatureHeader may contain transmissionId|transmissionTime|certUrl|authAlgo|transmissionSig
            String transmissionId = null;
            String transmissionTime = null;
            String certUrl = null;
            String authAlgo = null;
            String transmissionSig = null;
            if (signatureHeader != null) {
                String[] parts = signatureHeader.split("\\|", -1);
                if (parts.length >= 5) {
                    transmissionId = parts[0]; transmissionTime = parts[1]; certUrl = parts[2]; authAlgo = parts[3]; transmissionSig = parts[4];
                }
            }
            boolean ok = client.verifyWebhook(transmissionId, transmissionTime, certUrl, authAlgo, transmissionSig, this.webhookId, payload);
            if (!ok) {
                return "invalid";
            }
            JsonNode node = mapper.readTree(payload);
            String eventType = node.get("event_type").asText();
            JsonNode resource = node.get("resource");
            String id = null;
            if (resource != null && resource.has("id")) id = resource.get("id").asText();
            // return a simple normalized string for application layer to parse
            return mapper.createObjectNode().put("type", eventType).put("id", id).toString();
        } catch (Exception e) {
            return "error";
        }
    }

    public Map<String, Object> refund(String captureId, long amount, String currency) throws Exception {
        return client.refundCapture(captureId, amount, currency);
    }
}
