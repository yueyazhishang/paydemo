package com.yueyazhishang.paydemo.payment;

import com.yueyazhishang.paydemo.payment.application.CreatePaymentCommand;
import com.yueyazhishang.paydemo.payment.application.CreatePaymentService;
import com.yueyazhishang.paydemo.payment.domain.Payment;
import com.yueyazhishang.paydemo.payment.domain.PaymentRepository;
import com.yueyazhishang.paydemo.payment.shared.EventBus;
import com.yueyazhishang.paydemo.payment.shared.InMemoryEventBus;
import com.yueyazhishang.paydemo.payment.infra.adapter.ChannelAdapter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

public class CreatePaymentServiceUnitTest {

    @Test
    public void create_uses_adapter_and_publishes_event() {
        PaymentRepository paymentRepo = mock(PaymentRepository.class);
        ChannelAdapter stripeAdapter = mock(ChannelAdapter.class);
        ChannelAdapter paypalAdapter = mock(ChannelAdapter.class);
        InMemoryEventBus eventBus = new InMemoryEventBus();

        when(paymentRepo.findByOrderId("ORD-1")).thenReturn(Optional.empty());
        // when saving first time, return the same entity (repository saves in place in this demo)
        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        when(paymentRepo.save(captor.capture())).thenAnswer(invocation -> invocation.getArgument(0));

        CreatePaymentService svc = new CreatePaymentService(paymentRepo, stripeAdapter, paypalAdapter, eventBus);
        when(stripeAdapter.createPayment(any())).thenReturn(new ChannelAdapter.CreateResult(true, "stripe_ext_1", "raw"));

        CreatePaymentCommand cmd = new CreatePaymentCommand("ORD-1", BigDecimal.valueOf(5.00), "usd", "STRIPE");
        Payment p = svc.create(cmd);

        // verify adapter called
        verify(stripeAdapter, times(1)).createPayment(any());
        // verify payment saved and marked completed
        assertThat(p.getStatus()).isEqualTo(com.yueyazhishang.paydemo.payment.domain.PaymentStatus.COMPLETED);
        assertThat(p.getExternalId()).isEqualTo("stripe_ext_1");
    }
}
