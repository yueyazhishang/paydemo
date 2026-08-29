package com.yueyazhishang.paydemo.payment.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yueyazhishang.paydemo.payment.domain.Payment;
import com.yueyazhishang.paydemo.payment.domain.PaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class ProcessWebhookService {
    private final PaymentRepository paymentRepository;
    private final ObjectMapper mapper = new ObjectMapper();

    public ProcessWebhookService(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    @Transactional
    public void processPaypalWebhook(String normalizedJson) {
        try {
            JsonNode node = mapper.readTree(normalizedJson);
            String type = node.get("type").asText();
            String id = node.get("id").asText();
            // For demo: treat capture completed as payment completed
            if ("PAYMENT.CAPTURE.COMPLETED".equalsIgnoreCase(type) || "PAYMENT.CAPTURE.DENIED".equalsIgnoreCase(type)) {
                Optional<Payment> pOpt = paymentRepository.findByExternalId(id);
                if (pOpt.isPresent()) {
                    Payment p = pOpt.get();
                    if ("PAYMENT.CAPTURE.COMPLETED".equalsIgnoreCase(type)) {
                        p.markCompleted();
                    } else {
                        p.markFailed();
                    }
                    paymentRepository.save(p);
                }
            }
            // handle other event types as needed
        } catch (Exception e) {
            // log
        }
    }

    @Transactional
    public void processStripeWebhook(String normalizedJson) {
        try {
            JsonNode node = mapper.readTree(normalizedJson);
            String type = node.get("type").asText();
            String id = node.get("id").asText();

            // Map Stripe events to domain actions: payment_intent.succeeded -> COMPLETED, payment_intent.payment_failed -> FAILED
            if ("payment_intent.succeeded".equalsIgnoreCase(type) || "payment_intent.payment_failed".equalsIgnoreCase(type)) {
                Optional<Payment> pOpt = paymentRepository.findByExternalId(id);
                if (pOpt.isPresent()) {
                    Payment p = pOpt.get();
                    if ("payment_intent.succeeded".equalsIgnoreCase(type)) {
                        p.markCompleted();
                    } else {
                        p.markFailed();
                    }
                    paymentRepository.save(p);
                }
            }

            // Handle other Stripe event types as required
        } catch (Exception e) {
            // log
        }
    }
}
