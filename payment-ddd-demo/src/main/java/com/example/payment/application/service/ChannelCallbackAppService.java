package com.example.payment.application.service;

import com.example.payment.domain.gateway.CallbackRequest;
import com.example.payment.domain.gateway.ChannelCallbackMessage;
import com.example.payment.domain.gateway.GatewayException;
import com.example.payment.domain.gateway.PaymentGateway;
import com.example.payment.domain.payment.event.DomainEvent;
import com.example.payment.domain.payment.model.AmountMismatchException;
import com.example.payment.domain.payment.model.ChannelCallbackLog;
import com.example.payment.domain.payment.model.PaymentOrder;
import com.example.payment.domain.payment.model.RefundOrder;
import com.example.payment.domain.payment.repository.ChannelCallbackLogRepository;
import com.example.payment.domain.payment.repository.PaymentOrderRepository;
import com.example.payment.domain.payment.repository.RefundOrderRepository;
import com.example.payment.domain.service.GatewayRegistry;
import com.example.payment.domain.shared.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 渠道回调应用服务 —— 回调链路的编排核心。
 *
 * <p>完整链路（与设计文档「回调链路设计」章节对应）：
 * <ol>
 *   <li>留痕：回调原始报文全量落库（验签失败也留痕，审计/争议仲裁基准）</li>
 *   <li>验签解析：渠道适配器内完成，验签失败抛 GatewayException（接口层转渠道失败应答）</li>
 *   <li>幂等推进：状态机保证终态重复回调直接忽略；金额核对防串单/篡改</li>
 *   <li>事件发布：事务提交后由 AFTER_COMMIT 监听器触发上游业务方通知（带重试）</li>
 * </ol>
 * 掉单补偿（渠道回调未达）由 PaymentCompensationJob 查单兜底，本服务与之共用状态机。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelCallbackAppService {

    private final GatewayRegistry gatewayRegistry;
    private final PaymentOrderRepository paymentOrderRepository;
    private final RefundOrderRepository refundOrderRepository;
    private final ChannelCallbackLogRepository callbackLogRepository;
    private final ApplicationEventPublisher eventPublisher;
    /**
     * 编程式事务：只包住「状态推进+落库+发事件」段。
     * 原因：① 验签失败时的留痕必须留在事务外（否则随异常回滚丢失）；
     * ② 领域事件必须在事务内发布，事务提交后 AFTER_COMMIT 监听器才会触发上游通知；
     * ③ 编程式而非 @Transactional 注解，是因为验签段与事务段的边界在同一方法内。
     */
    private final TransactionTemplate transactionTemplate;

    /**
     * 处理渠道异步通知。
     *
     * @return 解析后的统一回调消息（接口层据此渲染渠道要求的应答）
     */
    public ChannelCallbackMessage handleCallback(CallbackRequest callbackRequest) {
        try {
            return doHandle(callbackRequest);
        } catch (GatewayException e) {
            // 验签失败：留痕后原样抛出，接口层渲染渠道失败应答（渠道会重试）
            saveLog(callbackRequest, ChannelCallbackLog.ProcessResult.SIGN_FAILED, null, e.getMessage());
            throw e;
        }
    }

    private ChannelCallbackMessage doHandle(CallbackRequest callbackRequest) {
        Channel channel = callbackRequest.getChannel();
        PaymentGateway gateway = gatewayRegistry.getGateway(channel);

        // ① 验签 + 解析，得到统一回调消息（防腐层核心动作）
        ChannelCallbackMessage message;
        try {
            message = gateway.parseCallback(callbackRequest);
        } catch (GatewayException e) {
            log.warn("渠道回调验签/解析失败: channel={}, error={}", channel, e.getMessage());
            throw e;
        }
        log.info("渠道回调: channel={}, type={}, ourTradeNo={}, success={}",
                channel, message.getCallbackType(), message.getOurTradeNo(), message.isSuccess());

        // ② 事务内路由处理：状态推进 + 落库 + 事件登记发布（原子）
        try {
            transactionTemplate.executeWithoutResult(tx -> {
                switch (message.getCallbackType()) {
                    case PAYMENT -> handlePaymentCallback(channel, message);
                    case REFUND -> handleRefundCallback(channel, message);
                }
            });
        } catch (IllegalArgumentException e) {
            saveLog(channel, message, ChannelCallbackLog.ProcessResult.ORDER_NOT_FOUND, e.getMessage());
            throw e;
        } catch (AmountMismatchException e) {
            // 聚合守护的金额不变量被破坏（防串单/篡改）
            saveLog(channel, message, ChannelCallbackLog.ProcessResult.AMOUNT_MISMATCH, e.getMessage());
            throw e;
        } catch (IllegalStateException e) {
            saveLog(channel, message, ChannelCallbackLog.ProcessResult.ERROR, e.getMessage());
            throw e;
        }

        saveLog(channel, message, ChannelCallbackLog.ProcessResult.SUCCESS, null);
        return message;
    }

    private void handlePaymentCallback(Channel channel, ChannelCallbackMessage message) {
        PaymentOrder order = paymentOrderRepository.findByPaymentId(message.getOurTradeNo())
                .orElseThrow(() -> new IllegalArgumentException(
                        "回调对应的支付单不存在: " + message.getOurTradeNo()));

        // 状态机幂等：终态订单收到重复回调直接返回（渠道重试友好）
        if (order.getStatus().isFinal()) {
            log.info("支付单[{}]已终态({})，忽略重复回调", order.getPaymentId(), order.getStatus());
            saveLog(channel, message,
                    ChannelCallbackLog.ProcessResult.IGNORED_DUPLICATE, null);
            return;
        }

        if (message.isSuccess()) {
            // 金额不变量由聚合内校验（succeed 内核对确认金额与应付金额）
            order.succeed(message.getChannelTradeNo(), message.getAmountMinor());
        } else {
            order.fail("渠道回调通知支付失败");
        }
        paymentOrderRepository.save(order);
        publishEvents(order);
    }

    private void handleRefundCallback(Channel channel, ChannelCallbackMessage message) {
        RefundOrder refund = refundOrderRepository.findByRefundId(message.getOurTradeNo())
                .orElseThrow(() -> new IllegalArgumentException(
                        "回调对应的退款单不存在: " + message.getOurTradeNo()));
        if (refund.getStatus().isFinal()) {
            log.info("退款单[{}]已终态，忽略重复回调", refund.getRefundId());
            saveLog(channel, message,
                    ChannelCallbackLog.ProcessResult.IGNORED_DUPLICATE, null);
            return;
        }
        if (message.isSuccess()) {
            refund.succeed(message.getChannelTradeNo());
        } else {
            refund.fail(message.getChannelTradeNo());
        }
        refundOrderRepository.save(refund);
        publishEvents(refund);
    }

    // ---------- 回调留痕 ----------

    private void saveLog(Channel channel, ChannelCallbackMessage message,
                         ChannelCallbackLog.ProcessResult result, String errorMessage) {
        try {
            callbackLogRepository.save(ChannelCallbackLog.record(
                    channel != null ? channel.name() : "UNKNOWN",
                    message.getCallbackType(),
                    message.getOurTradeNo(),
                    message.isSignVerified(),
                    message.isSuccess(),
                    result,
                    errorMessage,
                    message.getRawBody()));
        } catch (Exception ex) {
            // 留痕失败不阻断回调主流程，但必须告警
            log.error("回调留痕落库失败", ex);
        }
    }

    private void saveLog(CallbackRequest request, ChannelCallbackLog.ProcessResult result,
                         String ourTradeNo, String errorMessage) {
        try {
            callbackLogRepository.save(ChannelCallbackLog.record(
                    request.getChannel().name(),
                    com.example.payment.domain.gateway.CallbackType.PAYMENT,
                    ourTradeNo,
                    false,
                    false,
                    result,
                    errorMessage,
                    request.getBody()));
        } catch (Exception ex) {
            log.error("回调留痕落库失败", ex);
        }
    }

    private void publishEvents(Object aggregate) {
        var events = (aggregate instanceof PaymentOrder order)
                ? order.pullEvents()
                : ((RefundOrder) aggregate).pullEvents();
        for (DomainEvent event : events) {
            log.info("发布领域事件: {}", event.getClass().getSimpleName());
            eventPublisher.publishEvent(event);
        }
    }
}
