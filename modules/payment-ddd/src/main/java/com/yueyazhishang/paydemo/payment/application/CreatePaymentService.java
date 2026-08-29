package com.yueyazhishang.paydemo.payment.application;

import com.yueyazhishang.paydemo.payment.domain.Payment;
import com.yueyazhishang.paydemo.payment.domain.PaymentRepository;
import com.yueyazhishang.paydemo.payment.domain.PaymentStatus;
import com.yueyazhishang.paydemo.payment.domain.Money;
import com.yueyazhishang.paydemo.payment.infra.adapter.ChannelAdapter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

@Service
public class CreatePaymentService {

    private final PaymentRepository paymentRepository;
    private final ChannelAdapter stripeAdapter;
    private final ChannelAdapter paypalAdapter;

    public CreatePaymentService(PaymentRepository paymentRepository,
                                ChannelAdapter stripeAdapter,
                                ChannelAdapter paypalAdapter) {
        this.paymentRepository = paymentRepository;
        this.stripeAdapter = stripeAdapter;
        this.paypalAdapter = paypalAdapter;
    }

    @Transactional
    public Payment create(CreatePaymentCommand cmd) {
        // idempotency: if order exists return it
        Optional<Payment> existing = paymentRepository.findByOrderId(cmd.getOrderId());
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
