package com.yueyazhishang.paydemo.payment;

import com.yueyazhishang.paydemo.payment.shared.InMemoryEventBus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PaymentConfig {
    @Bean
    public InMemoryEventBus inMemoryEventBus() {
        return new InMemoryEventBus();
    }
}
