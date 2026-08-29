package com.yueyazhishang.paydemo.payment.infra.saga;

import com.yueyazhishang.paydemo.payment.application.RefundService;
import com.yueyazhishang.paydemo.payment.domain.events.RefundInitiatedEvent;
import com.yueyazhishang.paydemo.payment.shared.EventBus;
import org.springframework.stereotype.Component;

@Component
public class RefundSaga {
    private final RefundService refundService;

    public RefundSaga(EventBus bus, RefundService refundService) {
        this.refundService = refundService;
        bus.subscribe(RefundInitiatedEvent.class, this::onRefundInitiated);
    }

    private void onRefundInitiated(RefundInitiatedEvent e) {
        // synchronous processing in demo; in prod this would be async and resilient
        refundService.processRefund(e.getRefundId());
    }
}
