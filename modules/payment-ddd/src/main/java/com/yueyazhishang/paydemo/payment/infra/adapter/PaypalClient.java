package com.yueyazhishang.paydemo.payment.infra.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.Base64Utils;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Component
public class PaypalClient {
    private final RestTemplate rest;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;
    private final String clientId;
    private final String clientSecret;
    private String cachedToken;
    private Instant tokenExpiresAt = Instant.EPOCH;

    public PaypalClient(@Value("${paypal.clientId:}") String clientId,
                        @Value("${paypal.clientSecret:}") String clientSecret,
                        @Value("${paypal.sandbox:true}") boolean sandbox) {
        this.rest = new RestTemplate();
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.baseUrl = sandbox ? "https://api-m.sandbox.paypal.com" : "https://api-m.paypal.com";
    }

    private synchronized String getAccessToken() throws Exception {
        if (cachedToken != null && Instant.now().isBefore(tokenExpiresAt.minusSeconds(30))) {
            return cachedToken;
        }
        String url = baseUrl + "/v1/oauth2/token";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        String auth = clientId + ":" + clientSecret;
        headers.set("Authorization", "Basic " + Base64Utils.encodeToString(auth.getBytes()));
        HttpEntity<String> req = new HttpEntity<>("grant_type=client_credentials", headers);
        ResponseEntity<String> resp = rest.postForEntity(url, req, String.class);
        if (!resp.getStatusCode().is2xxSuccessful()) throw new RuntimeException("Failed to get token: " + resp.getStatusCode());
        JsonNode node = mapper.readTree(resp.getBody());
        cachedToken = node.get("access_token").asText();
        int expiresIn = node.get("expires_in").asInt(3600);
        tokenExpiresAt = Instant.now().plusSeconds(expiresIn);
        return cachedToken;
    }

    public Map<String, Object> createAndCaptureOrder(String orderId, long amount, String currency) throws Exception {
        // Create order
        String token = getAccessToken();
        String url = baseUrl + "/v2/checkout/orders";
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = new HashMap<>();
        body.put("intent", "CAPTURE");
        Map<String, Object> purchaseUnit = new HashMap<>();
        Map<String, Object> amountObj = new HashMap<>();
        amountObj.put("currency_code", currency.toUpperCase());
        amountObj.put("value", String.format("%.2f", amount / 100.0));
        purchaseUnit.put("amount", amountObj);
        body.put("purchase_units", new Object[]{purchaseUnit});

        HttpEntity<Map<String, Object>> req = new HttpEntity<>(body, headers);
        ResponseEntity<String> createResp = rest.postForEntity(url, req, String.class);
        if (!createResp.getStatusCode().is2xxSuccessful()) throw new RuntimeException("PayPal create order failed: " + createResp.getStatusCode());
        JsonNode createNode = mapper.readTree(createResp.getBody());
        String createdOrderId = createNode.get("id").asText();

        // Capture order
        String captureUrl = baseUrl + "/v2/checkout/orders/" + createdOrderId + "/capture";
        HttpEntity<Void> capReq = new HttpEntity<>(headers);
        ResponseEntity<String> capResp = rest.postForEntity(captureUrl, capReq, String.class);
        if (!capResp.getStatusCode().is2xxSuccessful()) throw new RuntimeException("PayPal capture failed: " + capResp.getStatusCode());
        JsonNode capNode = mapper.readTree(capResp.getBody());
        // Extract capture id
        JsonNode purchaseUnits = capNode.get("purchase_units");
        String captureId = null;
        if (purchaseUnits != null && purchaseUnits.isArray() && purchaseUnits.size() > 0) {
            JsonNode payments = purchaseUnits.get(0).get("payments");
            if (payments != null) {
                JsonNode captures = payments.get("captures");
                if (captures != null && captures.isArray() && captures.size() > 0) {
                    captureId = captures.get(0).get("id").asText();
                }
            }
        }
        Map<String, Object> result = new HashMap<>();
        result.put("orderId", createdOrderId);
        result.put("captureId", captureId);
        result.put("raw", capResp.getBody());
        return result;
    }

    public boolean verifyWebhook(String transmissionId, String transmissionTime, String certUrl, String authAlgo,
                                 String transmissionSig, String webhookId, String body) {
        // For demo: if clientId/secret not provided, skip verification
        if (clientId == null || clientId.isEmpty() || clientSecret == null || clientSecret.isEmpty() || webhookId == null || webhookId.isEmpty()) {
            return true;
        }
        try {
            String token = getAccessToken();
            String url = baseUrl + "/v1/notifications/verify-webhook-signature";
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(token);
            headers.setContentType(MediaType.APPLICATION_JSON);
            Map<String, Object> req = new HashMap<>();
            req.put("transmission_id", transmissionId);
            req.put("transmission_time", transmissionTime);
            req.put("cert_url", certUrl);
            req.put("auth_algo", authAlgo);
            req.put("transmission_sig", transmissionSig);
            req.put("webhook_id", webhookId);
            req.put("webhook_event", mapper.readTree(body));
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(req, headers);
            ResponseEntity<String> resp = rest.postForEntity(url, entity, String.class);
            if (!resp.getStatusCode().is2xxSuccessful()) return false;
            JsonNode node = mapper.readTree(resp.getBody());
            return "SUCCESS".equalsIgnoreCase(node.get("verification_status").asText());
        } catch (Exception e) {
            return false;
        }
    }

    public Map<String, Object> refundCapture(String captureId, long amount, String currency) throws Exception {
        String token = getAccessToken();
        String url = baseUrl + "/v2/payments/captures/" + captureId + "/refund";
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = new HashMap<>();
        Map<String, Object> amountObj = new HashMap<>();
        amountObj.put("value", String.format("%.2f", amount / 100.0));
        amountObj.put("currency_code", currency.toUpperCase());
        body.put("amount", amountObj);
        HttpEntity<Map<String, Object>> req = new HttpEntity<>(body, headers);
        ResponseEntity<String> resp = rest.postForEntity(url, req, String.class);
        if (!resp.getStatusCode().is2xxSuccessful()) throw new RuntimeException("PayPal refund failed: " + resp.getStatusCode());
        Map<String, Object> result = new HashMap<>();
        result.put("raw", resp.getBody());
        return result;
    }
}
