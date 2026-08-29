package com.example.payment.infrastructure.schedule;

import com.example.payment.application.dto.PaymentOrderDTO;
import com.example.payment.application.service.PaymentAppService;
import com.example.payment.application.service.UpstreamNotifyService;
import com.example.payment.domain.payment.model.PaymentOrder;
import com.example.payment.domain.payment.model.PaymentStatus;
import com.example.payment.domain.payment.repository.PaymentOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 掉单补偿定时任务 —— 回调链路的「主动兜底」半边。
 *
 * <p>渠道回调可能因网络/重试策略丢失（掉单），支付单会滞留 PAYING 态：
 * <ul>
 *   <li>未超时：主动调渠道查单（query）同步状态 —— 防止「渠道成功、我方 PAYING」的单边账</li>
 *   <li>已超时且渠道侧仍无成功结果：关单（close），防止迟到扣款</li>
 * </ul>
 * 与渠道回调共用同一套聚合状态机，天然幂等。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentCompensationJob {

    private final PaymentOrderRepository paymentOrderRepository;
    private final PaymentAppService paymentAppService;

    /** 每 60 秒扫描一次 PAYING 支付单（生产环境建议分片 + 渠道限流） */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void compensatePayingOrders() {
        List<PaymentOrder> payingOrders = paymentOrderRepository.findByStatus(PaymentStatus.PAYING);
        if (payingOrders.isEmpty()) {
            return;
        }
        log.info("掉单补偿开始: payingCount={}", payingOrders.size());
        Instant now = Instant.now();
        for (PaymentOrder order : payingOrders) {
            try {
                // 1. 查单兜底：确认渠道侧真实状态
                PaymentOrderDTO synced = paymentAppService.queryAndSyncPayment(order.getPaymentId());

                // 2. 超时且仍未终态 → 关单（过期判定属于聚合知识）
                if (PaymentStatus.PAYING.name().equals(synced.getStatus())
                        && order.isExpired(now)) {
                    log.info("支付单[{}]已过期仍未支付，执行关单", order.getPaymentId());
                    paymentAppService.closePayment(order.getPaymentId());
                }
            } catch (Exception e) {
                // 单笔失败不影响批次
                log.warn("掉单补偿异常: paymentId={}, error={}", order.getPaymentId(), e.getMessage());
            }
        }
    }
}
