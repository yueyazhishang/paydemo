package com.yueyazhishang.paydemo.payment.infra.adapter;

import com.yueyazhishang.paydemo.payment.application.CreatePaymentCommand;

public interface ChannelAdapter {
    class CreateResult {
        private final boolean success;
        private final String externalId;
        private final String raw;

        public CreateResult(boolean success, String externalId, String raw) {
            this.success = success;
            this.externalId = externalId;
            this.raw = raw;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getExternalId() {
            return externalId;
        }

        public String getRaw() {
            return raw;
        }
    }

    CreateResult createPayment(CreatePaymentCommand command);

    /**
     * Handle webhook payload for this channel. Return a normalized event string (e.g., payment succeeded id)
     */
    String handleWebhook(String payload, String signatureHeader);
}
