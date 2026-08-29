package com.example.payment.application.service;

import com.example.payment.application.command.RefundCommand;
import com.example.payment.application.dto.RefundOrderDTO;
import com.example.payment.domain.gateway.GatewayRefundRequest;
import com.example.payment.domain.gateway.GatewayRefundResult;
import com.example.payment.domain.payment.event.DomainEvent;
import com.example.payment.domain.payment.model.PaymentOrder;
import com.example.payment.domain.payment.model.RefundOrder;
import com.example.payment.domain.payment.model.RefundStatus;
import com.example.payment.domain.payment.repository.PaymentOrderRepository;
import com.example.payment.domain.payment.repository.RefundOrderRepository;
import com.example.payment.domain.service.GatewayRegistry;
import com.example.payment.domain.service.RefundDomainService;
import com.example.payment.domain.shared.Channel;
import com.example.payment.domain.shared.Currency;
import com.example.payment.domain.shared.Money;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 退款应用服务：跨聚合校验（领域服务）→ 渠道退款 → 状态推进。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundAppService {

    private final PaymentOrderRepository paymentOrderRepository;
    private final RefundOrderRepository refundOrderRepository;
    private final GatewayRegistry gatewayRegistry;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public RefundOrderDTO refund(RefundCommand cmd) {
        // 1. 装载原支付聚合
        PaymentOrder payment = paymentOrderRepository.findByPaymentId(cmd.getPaymentId())
                .orElseThrow(() -> new IllegalArgumentException("支付单不存在: " + cmd.getPaymentId()));

        // 2. 领域服务跨聚合校验可退金额并构造退款聚合
        Money refundAmount = Money.ofMinor(cmd.getRefundAmountMinor(),
                Currency.valueOf(cmd.getCurrency()));
        List<RefundOrder> activeRefunds = refundOrderRepository.findByPaymentIdAndStatusIn(
                cmd.getPaymentId(), List.of(RefundStatus.SUBMITTED, RefundStatus.SUCCESS));
        RefundOrder refund = RefundDomainService.createRefund(payment, activeRefunds,
                refundAmount, cmd.getReason());
        refundOrderRepository.save(refund);

        // 3. 调用渠道退款（防腐层）
        var result = gatewayRegistry.getGateway(payment.getChannel()).refund(
                GatewayRefundRequest.builder()
                        .refundId(refund.getRefundId())
                        .paymentId(payment.getPaymentId())
                        .channelTradeNo(payment.getChannelTradeNo())
                        .refundAmount(refundAmount)
                        .reason(cmd.getReason())
                        .build());

        // 4. 状态推进：同步成功 / 受理等待回调 / 失败
        switch (result.getStatus()) {
            case SUCCESS -> refund.succeed(result.getChannelRefundNo());
            case ACCEPTED -> refund.submitToChannel(result.getChannelRefundNo());
            case FAILED -> {
                refund.submitToChannel(result.getChannelRefundNo());
                refund.fail(result.getChannelRefundNo());
            }
        }
        refundOrderRepository.save(refund);
        publishEvents(refund);
        return toDto(refund);
    }

    public RefundOrderDTO getRefund(String refundId) {
        return refundOrderRepository.findByRefundId(refundId)
                .map(this::toDto)
                .orElseThrow(() -> new IllegalArgumentException("退款单不存在: " + refundId));
    }

    private void publishEvents(RefundOrder refund) {
        for (DomainEvent event : refund.pullEvents()) {
            log.info("发布领域事件: {}", event.getClass().getSimpleName());
            eventPublisher.publishEvent(event);
        }
    }

    private RefundOrderDTO toDto(RefundOrder refund) {
        return RefundOrderDTO.builder()
                .refundId(refund.getRefundId())
                .paymentId(refund.getPaymentId())
                .refundAmountMinor(refund.getRefundAmount().getAmountMinor())
                .currency(refund.getRefundAmount().getCurrency().name())
                .status(refund.getStatus().name())
                .channelRefundNo(refund.getChannelRefundNo())
                .build();
    }
}
