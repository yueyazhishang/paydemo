package com.yueyazhishang.paydemo.payment.application;

import com.yueyazhishang.paydemo.payment.domain.PaymentRepository;
import com.yueyazhishang.paydemo.payment.domain.Refund;
import com.yueyazhishang.paydemo.payment.domain.RefundRepository;
import com.yueyazhishang.paydemo.payment.infra.adapter.PaypalAdapter;
import com.yueyazhishang.paydemo.payment.shared.EventBus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
public class RefundService {
    private final RefundRepository refundRepository;
    private final PaymentRepository paymentRepository;
    private final PaypalAdapter paypalAdapter;
    private final EventBus eventBus;

    public RefundService(RefundRepository refundRepository, PaymentRepository paymentRepository, PaypalAdapter paypalAdapter, EventBus eventBus) {
        this.refundRepository = refundRepository;
        this.paymentRepository = paymentRepository;
        this.paypalAdapter = paypalAdapter;
        this.eventBus = eventBus;
    }

    @Transactional
    public Refund initiateRefund(Long paymentId, BigDecimal amount) {
        Refund r = new Refund(paymentId, amount);
        refundRepository.save(r);
        eventBus.publish(new com.yueyazhishang.paydemo.payment.domain.events.RefundInitiatedEvent(r.getId(), paymentId));
        return r;
    }

    @Transactional
    public void processRefund(Long refundId) {
        Refund r = refundRepository.findById(refundId).orElseThrow();
        r.markProcessing();
        refundRepository.save(r);
        // find payment to get external id
        com.yueyazhishang.paydemo.payment.domain.Payment p = paymentRepository.findById(r.getPaymentId()).orElseThrow();
        try {
            // call paypal refund for demo; in real system route by channel
            String captureId = p.getExternalId();
            long amountCents = r.getAmount().multiply(java.math.BigDecimal.valueOf(100)).longValue();
            var res = paypalAdapter.refund(captureId, amountCents, p.getCurrency());
            // handle result
            r.markCompleted((String)res.get("id"));
        } catch (Exception e) {
            r.markFailed();
        }
        refundRepository.save(r);
        eventBus.publish(new com.yueyazhishang.paydemo.payment.domain.events.RefundCompletedEvent(r.getId(), r.getExternalId()));
    }
}
