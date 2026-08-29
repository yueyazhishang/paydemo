package com.example.payment.application.service;

import com.example.payment.application.command.CreatePaymentCommand;
import com.example.payment.application.dto.PaymentOrderDTO;
import com.example.payment.domain.gateway.GatewayPayRequest;
import com.example.payment.domain.gateway.GatewayPayResult;
import com.example.payment.domain.gateway.PaymentGateway;
import com.example.payment.domain.payment.event.DomainEvent;
import com.example.payment.domain.payment.model.PaymentOrder;
import com.example.payment.domain.payment.model.PaymentStatus;
import com.example.payment.domain.payment.repository.PaymentOrderRepository;
import com.example.payment.domain.service.GatewayRegistry;
import com.example.payment.domain.shared.Channel;
import com.example.payment.domain.shared.Currency;
import com.example.payment.domain.shared.Money;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 支付应用服务：收单/查单兜底/关单用例编排。
 * 应用层只做编排（事务、幂等、调渠道、发事件），不含业务规则——规则都在聚合与领域服务里。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentAppService {

    private final PaymentOrderRepository paymentOrderRepository;
    private final GatewayRegistry gatewayRegistry;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 收单：创建支付单 → 渠道预下单 → 落库 → 返回收银台要素。
     *
     * @param notifyUrl 回调地址（由接口层基于配置拼接，应用层不关心 Web 细节）
     */
    @Transactional
    public PaymentOrderDTO createPayment(CreatePaymentCommand cmd, String notifyUrl) {
        Channel channel = Channel.valueOf(cmd.getChannel());

        // 幂等：同一业务单号 + 渠道只允许一笔有效支付单（唯一索引兜底）
        var existing = paymentOrderRepository.findByBizOrderNoAndChannel(cmd.getBizOrderNo(), channel);
        if (existing.isPresent()) {
            log.info("支付单已存在，幂等返回: bizOrderNo={}, channel={}", cmd.getBizOrderNo(), channel);
            return toDto(existing.get());
        }

        Money amount = Money.ofMinor(cmd.getAmountMinor(), Currency.valueOf(cmd.getCurrency()));

        // 1. 创建聚合（INIT），记录上游业务方的结果通知地址
        PaymentOrder order = PaymentOrder.create(
                cmd.getBizOrderNo(), cmd.getMerchantId(), amount, channel, 30,
                cmd.getMerchantNotifyUrl());

        // 2. 渠道预下单（防腐层端口）
        PaymentGateway gateway = gatewayRegistry.getGateway(channel);
        GatewayPayResult payResult = gateway.prepay(GatewayPayRequest.builder()
                .paymentId(order.getPaymentId())
                .bizOrderNo(cmd.getBizOrderNo())
                .amount(amount)
                .subject(cmd.getSubject())
                .notifyUrl(notifyUrl)
                .buyerId(cmd.getBuyerId())
                .build());

        // 3. 状态机流转
        if (payResult.isSuccess()) {
            order.submitToChannel(payResult.getPayType().name(), payResult.getPayData(),
                    payResult.getChannelTradeNo());
        } else {
            order.failOnSubmit(payResult.getErrorMessage());
        }

        // 4. 落库 + 发布事件
        paymentOrderRepository.save(order);
        publishEvents(order);
        return toDto(order);
    }

    public PaymentOrderDTO getPayment(String paymentId) {
        return paymentOrderRepository.findByPaymentId(paymentId)
                .map(this::toDto)
                .orElseThrow(() -> new IllegalArgumentException("支付单不存在: " + paymentId));
    }

    /**
     * 查单兜底：对 PAYING 状态的支付单向渠道主动查询，防回调丢失造成单边账。
     * （生产中由定时任务批量驱动，这里暴露为手动触发入口）
     */
    @Transactional
    public PaymentOrderDTO queryAndSyncPayment(String paymentId) {
        PaymentOrder order = paymentOrderRepository.findByPaymentId(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("支付单不存在: " + paymentId));
        if (order.getStatus() != PaymentStatus.PAYING) {
            return toDto(order); // 非支付中无需同步
        }
        var result = gatewayRegistry.getGateway(order.getChannel()).query(paymentId);
        switch (result.getStatus()) {
            case SUCCESS -> {
                // 金额不变量由聚合校验；渠道未回传实付金额时传 null 跳过核对
                order.succeed(result.getChannelTradeNo(),
                        result.getPaidAmount() != null ? result.getPaidAmount().getAmountMinor() : null);
                paymentOrderRepository.save(order);
                publishEvents(order);
            }
            case FAILED -> {
                order.fail("渠道查单返回失败");
                paymentOrderRepository.save(order);
                publishEvents(order);
            }
            default -> log.info("查单兜底: 订单[{}]渠道侧状态={}", paymentId, result.getStatus());
        }
        return toDto(order);
    }

    /** 超时关单 */
    @Transactional
    public PaymentOrderDTO closePayment(String paymentId) {
        PaymentOrder order = paymentOrderRepository.findByPaymentId(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("支付单不存在: " + paymentId));
        order.close();
        paymentOrderRepository.save(order);
        publishEvents(order);
        return toDto(order);
    }

    private void publishEvents(PaymentOrder order) {
        for (DomainEvent event : order.pullEvents()) {
            log.info("发布领域事件: {}", event.getClass().getSimpleName());
            eventPublisher.publishEvent(event);
        }
    }

    private PaymentOrderDTO toDto(PaymentOrder order) {
        return PaymentOrderDTO.builder()
                .paymentId(order.getPaymentId())
                .bizOrderNo(order.getBizOrderNo())
                .status(order.getStatus().name())
                .channel(order.getChannel().name())
                .amountMinor(order.getAmount().getAmountMinor())
                .currency(order.getAmount().getCurrency().name())
                .payType(order.getPayType())
                .payParams(order.getPayParams())
                .build();
    }
}
