package com.example.payment.application.service;

import com.example.payment.domain.gateway.UpstreamNotifier;
import com.example.payment.domain.payment.event.PaymentClosedEvent;
import com.example.payment.domain.payment.event.PaymentSucceededEvent;
import com.example.payment.domain.payment.event.RefundSucceededEvent;
import com.example.payment.domain.payment.model.MerchantNotifyTask;
import com.example.payment.domain.payment.model.PaymentOrder;
import com.example.payment.domain.payment.repository.MerchantNotifyTaskRepository;
import com.example.payment.domain.payment.repository.PaymentOrderRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.List;

/**
 * 上游通知应用服务 —— 回调链路的最后一环。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>事务边界解耦</b>：监听 AFTER_COMMIT 阶段的领域事件，保证「上游可见之前，
 *       我方状态一定已提交」，避免通知早于落库导致的幻读</li>
 *   <li><b>任务化</b>：通知先落 {@link MerchantNotifyTask}（WAITING）再执行 HTTP 调用，
 *       任何时刻崩溃都可由重试任务续跑（at-least-once，上游须幂等）</li>
 *   <li><b>退避重试</b>：失败按 1/5/15/30/60 分钟指数退避，共 6 次机会；
 *       耗尽后 EXHAUSTED，交由人工/对账兜底</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UpstreamNotifyService {

    private final MerchantNotifyTaskRepository notifyTaskRepository;
    private final PaymentOrderRepository paymentOrderRepository;
    private final UpstreamNotifier upstreamNotifier;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ---------- 事件监听（事务提交后触发） ----------

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentSucceeded(PaymentSucceededEvent event) {
        paymentOrderRepository.findByPaymentId(event.getPaymentId())
                .filter(order -> order.getMerchantNotifyUrl() != null)
                .ifPresent(order -> createAndExecuteTask(order.getPaymentId(),
                        "PAYMENT_SUCCEEDED", order.getMerchantNotifyUrl(),
                        buildPaymentPayload(event)));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRefundSucceeded(RefundSucceededEvent event) {
        // 退款通知地址沿用原支付单上登记的上游地址
        paymentOrderRepository.findByPaymentId(event.getPaymentId())
                .filter(order -> order.getMerchantNotifyUrl() != null)
                .ifPresent(order -> createAndExecuteTask(event.getRefundId(),
                        "REFUND_SUCCEEDED", order.getMerchantNotifyUrl(),
                        buildRefundPayload(event)));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentClosed(PaymentClosedEvent event) {
        paymentOrderRepository.findByPaymentId(event.getPaymentId())
                .filter(order -> order.getMerchantNotifyUrl() != null)
                .ifPresent(order -> createAndExecuteTask(order.getPaymentId(),
                        "PAYMENT_CLOSED", order.getMerchantNotifyUrl(),
                        buildClosedPayload(event)));
    }

    // ---------- 重试（由定时任务驱动） ----------

    /** 扫描到期任务并重试 */
    public int retryPendingTasks() {
        List<MerchantNotifyTask> dueTasks =
                notifyTaskRepository.findDueTasks(Instant.now());
        int success = 0;
        for (MerchantNotifyTask task : dueTasks) {
            if (executeTask(task)) {
                success++;
            }
        }
        if (!dueTasks.isEmpty()) {
            log.info("上游通知重试完成: total={}, success={}", dueTasks.size(), success);
        }
        return dueTasks.size();
    }

    // ---------- 内部 ----------

    private void createAndExecuteTask(String relatedTradeNo, String eventType,
                                      String notifyUrl, String payload) {
        MerchantNotifyTask task = MerchantNotifyTask.create(
                relatedTradeNo, eventType, notifyUrl, payload);
        notifyTaskRepository.save(task);
        executeTask(task);
    }

    private boolean executeTask(MerchantNotifyTask task) {
        try {
            boolean acked = upstreamNotifier.notify(task.getNotifyUrl(), task.getPayload());
            if (acked) {
                task.markSuccess();
                notifyTaskRepository.save(task);
                log.info("上游通知成功: task={}, event={}, trade={}",
                        task.getTaskId(), task.getEventType(), task.getRelatedTradeNo());
                return true;
            }
            task.markFailed("上游应答非成功标记");
        } catch (Exception e) {
            task.markFailed(e.getMessage());
        }
        notifyTaskRepository.save(task);
        log.warn("上游通知失败: task={}, retryCount={}, nextRetry={}, error={}",
                task.getTaskId(), task.getRetryCount(), task.getNextRetryTime(),
                task.getLastErrorMessage());
        return false;
    }

    // ---------- 通知报文（Published Language：对上游的稳定契约） ----------

    private String buildPaymentPayload(PaymentSucceededEvent event) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("eventType", "PAYMENT_SUCCEEDED");
        node.put("paymentId", event.getPaymentId());
        node.put("bizOrderNo", event.getBizOrderNo());
        node.put("amountMinor", event.getAmountMinor());
        node.put("currency", event.getCurrency());
        node.put("channelTradeNo", event.getChannelTradeNo());
        return node.toString();
    }

    private String buildRefundPayload(RefundSucceededEvent event) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("eventType", "REFUND_SUCCEEDED");
        node.put("refundId", event.getRefundId());
        node.put("paymentId", event.getPaymentId());
        node.put("refundAmountMinor", event.getRefundAmountMinor());
        node.put("currency", event.getCurrency());
        node.put("channelRefundNo", event.getChannelRefundNo());
        return node.toString();
    }

    private String buildClosedPayload(PaymentClosedEvent event) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("eventType", "PAYMENT_CLOSED");
        node.put("paymentId", event.getPaymentId());
        node.put("bizOrderNo", event.getBizOrderNo());
        return node.toString();
    }
}
