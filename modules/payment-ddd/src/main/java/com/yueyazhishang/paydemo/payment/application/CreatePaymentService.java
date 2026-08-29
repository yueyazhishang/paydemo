package com.yueyazhishang.paydemo.payment.application;

import com.yueyazhishang.paydemo.payment.domain.Payment;
import com.yueyazhishang.paydemo.payment.domain.PaymentRepository;
import com.yueyazhishang.paydemo.payment.domain.RefundRepository;
import com.yueyazhishang.paydemo.payment.infra.adapter.ChannelAdapter;
import com.yueyazhishang.paydemo.payment.shared.EventBus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
public class CreatePaymentService {

    private final PaymentRepository paymentRepository;
    private final ChannelAdapter stripeAdapter;
    private final ChannelAdapter paypalAdapter;
    private final EventBus eventBus;

    public CreatePaymentService(PaymentRepository paymentRepository,
                                ChannelAdapter stripeAdapter,
                                ChannelAdapter paypalAdapter,
                                EventBus eventBus) {
        this.paymentRepository = paymentRepository;
        this.stripeAdapter = stripeAdapter;
        this.paypalAdapter = paypalAdapter;
        this.eventBus = eventBus;
    }

    @Transactional
    public Payment create(CreatePaymentCommand cmd) {
        // idempotency: if order exists return it
        var existing = paymentRepository.findByOrderId(cmd.getOrderId());
        if (existing.isPresent()) return existing.get();

        Payment p = new Payment(cmd.getOrderId(), cmd.getAmount(), cmd.getCurrency());
        paymentRepository.save(p);

        // simple routing
        if ("STRIPE".equalsIgnoreCase(cmd.getPaymentMethod())) {
            p.markPending();
            paymentRepository.save(p);
            ChannelAdapter.CreateResult r = stripeAdapter.createPayment(cmd);
            if (r.isSuccess()) {
                p.markAuthorized(r.getExternalId());
                p.markCompleted();
                eventBus.publish(new com.yueyazhishang.paydemo.payment.domain.events.PaymentAuthorizedEvent(p.getId(), r.getExternalId()));
            } else {
                p.markFailed();
            }
            paymentRepository.save(p);
        } else if ("PAYPAL".equalsIgnoreCase(cmd.getPaymentMethod())) {
            p.markPending();
            paymentRepository.save(p);
            ChannelAdapter.CreateResult r = paypalAdapter.createPayment(cmd);
            if (r.isSuccess()) {
                p.markAuthorized(r.getExternalId());
                p.markCompleted();
                eventBus.publish(new com.yueyazhishang.paydemo.payment.domain.events.PaymentAuthorizedEvent(p.getId(), r.getExternalId()));
            } else {
                p.markFailed();
            }
            paymentRepository.save(p);
        } else {
            // other channels are stubbed for demo
            p.markFailed();
            paymentRepository.save(p);
        }

        return p;
    }
}
