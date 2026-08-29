package com.yueyazhishang.paydemo.payment;

import com.yueyazhishang.paydemo.payment.domain.Payment;
import com.yueyazhishang.paydemo.payment.domain.PaymentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
public class PaymentAggregateTest {

    @Autowired
    private PaymentRepository paymentRepository;

    @Test
    @Transactional
    public void createAndTransition() {
        Payment p = new Payment("TST-1", BigDecimal.valueOf(5.00), "usd");
        paymentRepository.save(p);
        p.markPending();
        paymentRepository.save(p);
        p.markAuthorized("EXT123");
        paymentRepository.save(p);
        p.markCompleted();
        paymentRepository.save(p);

        Payment loaded = paymentRepository.findByOrderId("TST-1").orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(com.yueyazhishang.paydemo.payment.domain.PaymentStatus.COMPLETED);
        assertThat(loaded.getExternalId()).isEqualTo("EXT123");
    }
}
