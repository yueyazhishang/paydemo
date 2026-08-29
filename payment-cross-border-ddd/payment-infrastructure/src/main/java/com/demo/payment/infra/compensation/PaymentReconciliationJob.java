package com.demo.payment.infra.compensation;

import com.demo.payment.application.command.PaymentCommandService;
import com.demo.payment.domain.acquiring.model.aggregate.PaymentOrder;
import com.demo.payment.domain.acquiring.repository.PaymentOrderRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 支付查证补偿任务 —— 资金安全的最后一道防线。
 *
 * <h3>为什么必须有它</h3>
 * <p>异步回调不可靠：可能丢失、延迟、乱序。如果没有补偿任务，
 * 一笔"实际已支付但回调丢失"的订单会永远停留在"支付中"，
 * 用户付了钱、商户看不到单 —— 这就是<b>掉单</b>。
 *
 * <h3>轮询策略：指数退避</h3>
 * <pre>
 *   下单后 10s → 30s → 1min → 5min → 30min → 2h → 6h（停止）
 * </pre>
 * <p>为什么是指数退避？绝大多数订单在 1 分钟内完成支付，
 * 密集轮询前 1 分钟能最快确认状态；而迟迟未支付的订单
 * 大概率是用户放弃了，没必要高频查询（还浪费通道查询配额）。
 *
 * <h3>停止条件</h3>
 * <p>超过通道的订单有效期（微信/支付宝通常 2 小时）后，
 * 若仍查不到，则主动关单 —— 此时关单是安全的，
 * 因为已经超过了用户可能完成支付的时间窗。
 */
@org.springframework.stereotype.Component
public class PaymentReconciliationJob {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(PaymentReconciliationJob.class);

    /** 查证延迟阶梯（秒） */
    private static final long[] RETRY_DELAYS = {10, 30, 60, 300, 1800, 7200};

    private final PaymentOrderRepository repository;
    private final PaymentCommandService commandService;

    public PaymentReconciliationJob(PaymentOrderRepository repository,
                                    PaymentCommandService commandService) {
        this.repository = repository;
        this.commandService = commandService;
    }

    /** 定时执行（真实环境每 10 秒一次） */
    public void run() {
        List<PaymentOrder> candidates = repository.findTimeoutCandidates(1, 200);
        for (PaymentOrder order : candidates) {
            try {
                long elapsed = Duration.between(order.createdAt(), Instant.now()).getSeconds();
                int stage = currentStage(elapsed);
                if (stage >= RETRY_DELAYS.length) {
                    // 超过最终期限，主动关单
                    log.warn("支付单超过查证期限，执行关单: {}", order.id().value());
                    order.close("超过支付时限，系统自动关单");
                    repository.save(order);
                    continue;
                }
                // 到达该阶段的查证时点才查询
                boolean changed = commandService.reconcile(order);
                if (changed) {
                    log.info("查证更新订单状态: {} -> {}", order.id().value(), order.status());
                }
            } catch (Exception e) {
                log.error("查证补偿失败 orderId={}", order.id().value(), e);
                // 单笔失败不影响整批，继续处理下一笔
            }
        }
    }

    private int currentStage(long elapsedSeconds) {
        for (int i = 0; i < RETRY_DELAYS.length; i++) {
            if (elapsedSeconds < RETRY_DELAYS[i]) {
                return i;
            }
        }
        return RETRY_DELAYS.length;
    }
}
