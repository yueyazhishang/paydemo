package com.demo.payment.infra.config;

import com.demo.payment.application.command.NotificationService;
import com.demo.payment.application.command.PaymentCommandService;
import com.demo.payment.application.command.RefundCommandService;
import com.demo.payment.application.idempotency.IdempotencyGuard;
import com.demo.payment.application.idempotency.IdempotencyStore;
import com.demo.payment.application.outbox.OutboxService;
import com.demo.payment.application.outbox.OutboxStore;
import com.demo.payment.domain.acquiring.repository.PaymentOrderRepository;
import com.demo.payment.domain.acquiring.service.RefundPolicyService;
import com.demo.payment.domain.acquiring.service.RefundPolicyServiceImpl;
import com.demo.payment.domain.channel.model.ChannelCode;
import com.demo.payment.domain.channel.route.ChannelRouter;
import com.demo.payment.domain.channel.spi.PaymentChannelPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * 应用服务装配。
 *
 * <p>注意所有 Bean 的类型都是<b>领域层或应用层定义的接口/类</b>，
 * 基础设施只负责"把它们拼起来"。这就是六边形架构的装配层职责。
 */
@Configuration
public class ApplicationServiceConfiguration {

    @Bean
    public RefundPolicyService refundPolicyService() {
        return new RefundPolicyServiceImpl();
    }

    @Bean
    public IdempotencyGuard idempotencyGuard(IdempotencyStore store) {
        return new IdempotencyGuard(store);
    }

    @Bean
    public OutboxService outboxService(OutboxStore store) {
        // 简化序列化器：真实环境用 Jackson
        return new OutboxService(store, event -> event.getClass().getSimpleName()
                + "|" + event.aggregateId() + "|" + event.occurredAt());
    }

    @Bean
    public PaymentCommandService paymentCommandService(
            PaymentOrderRepository repository,
            ChannelRouter router,
            Map<ChannelCode, PaymentChannelPort> channelMap,
            IdempotencyGuard idempotencyGuard,
            OutboxService outboxService) {
        return new PaymentCommandService(repository, router, channelMap,
                idempotencyGuard, outboxService);
    }

    @Bean
    public RefundCommandService refundCommandService(
            PaymentOrderRepository repository,
            Map<ChannelCode, PaymentChannelPort> channelMap,
            RefundPolicyService refundPolicy,
            OutboxService outboxService) {
        return new RefundCommandService(repository, channelMap, refundPolicy, outboxService);
    }

    @Bean
    public NotificationService notificationService(
            PaymentOrderRepository repository,
            Map<ChannelCode, PaymentChannelPort> channelMap,
            OutboxService outboxService) {
        return new NotificationService(repository, channelMap, outboxService);
    }
}
