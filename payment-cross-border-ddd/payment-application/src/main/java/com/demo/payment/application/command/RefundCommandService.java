package com.demo.payment.application.command;

import com.demo.payment.application.outbox.OutboxService;
import com.demo.payment.domain.acquiring.model.aggregate.PaymentOrder;
import com.demo.payment.domain.acquiring.model.entity.RefundOrder;
import com.demo.payment.domain.acquiring.repository.PaymentOrderRepository;
import com.demo.payment.domain.acquiring.service.RefundCheckResult;
import com.demo.payment.domain.acquiring.service.RefundPolicyService;
import com.demo.payment.domain.channel.model.ChannelResultStatus;
import com.demo.payment.domain.channel.spi.*;
import com.demo.payment.domain.channel.model.ChannelCapability;
import com.demo.payment.shared.money.Money;
import com.demo.payment.shared.util.IdGenerator;

import java.util.Map;
import java.util.concurrent.locks.Lock;

/**
 * 退款命令服务。
 *
 * <h3>为什么退款必须用分布式锁 + 聚合内校验</h3>
 * <p>并发退款是最经典的资金安全事故场景：
 * <pre>
 *   线程A：读订单 → 已退 0 → 校验通过 → 退款 100
 *   线程B：读订单 → 已退 0 → 校验通过 → 退款 100
 *   结果：原单 100 元，实际退了 200 元
 * </pre>
 *
 * <p>本实现三重防护：
 * <ol>
 *   <li><b>分布式锁</b>：按订单维度串行化，从物理上杜绝并发</li>
 *   <li><b>聚合内校验</b>：PaymentOrder 内部同一把锁内完成"读-校验-写"</li>
 *   <li><b>DB 约束</b>：退款金额 sum 的 CHECK 约束（最终兜底）</li>
 * </ol>
 *
 * <p>只做其中任何一层都不够 —— 锁可能失效（Redis 抖动），
 * 聚合校验可能在极端并发下被绕过（若未来改成独立聚合），
 * DB 约束则太晚（用户已收到退款成功的响应）。三层是纵深防御。
 */
public class RefundCommandService {

    private final PaymentOrderRepository repository;
    private final Map<com.demo.payment.domain.channel.model.ChannelCode, PaymentChannelPort> channels;
    private final RefundPolicyService refundPolicy;
    private final OutboxService outboxService;

    public RefundCommandService(PaymentOrderRepository repository,
                                Map<com.demo.payment.domain.channel.model.ChannelCode, PaymentChannelPort> channels,
                                RefundPolicyService refundPolicy,
                                OutboxService outboxService) {
        this.repository = repository;
        this.channels = channels;
        this.refundPolicy = refundPolicy;
        this.outboxService = outboxService;
    }

    /**
     * 发起退款。
     */
    public RefundOrder refund(String merchantId, String merchantOrderNo,
                              Money refundAmount, String reason) {
        PaymentOrder order = repository.findByMerchantOrderNo(merchantId, merchantOrderNo)
                .orElseThrow(() -> new IllegalArgumentException("订单不存在: " + merchantOrderNo));

        // ---- 第一层防护：分布式锁（订单维度串行化）----
        Lock lock = repository.obtainLock(order.id());
        lock.lock();
        try {
            var attempt = order.currentAttempt();
            if (attempt == null) {
                throw new IllegalStateException("订单无通道尝试记录，无法退款");
            }

            PaymentChannelPort channel = channels.get(attempt.channelCode());
            ChannelCapability capability = channel.capability();

            // ---- 第二层防护：策略校验（含通道能力）----
            RefundCheckResult check = refundPolicy.check(order, capability, refundAmount);
            if (!check.allowed()) {
                throw new IllegalStateException("退款被拒绝：" + check.rejectReason());
            }

            // ---- 第三层防护：聚合内累计金额校验（防并发超额）----
            int windowDays = capability.refundWindowDays() == null ? 0 : capability.refundWindowDays();
            RefundOrder refund = order.requestRefund(refundAmount, reason, windowDays);

            // 先落库占用额度，再调通道
            repository.save(order);
            outboxService.capture("PaymentOrder", order.id().value(), order.pullDomainEvents());

            // 调用通道退款
            String outRefundNo = IdGenerator.refundOrderId();
            RefundCommand refundCommand = new RefundCommand(
                    attempt.outTradeNo(), outRefundNo, refundAmount, order.amount(),
                    reason, order.notifyUrl(), outRefundNo);

            RefundResponse response = channel.refund(refundCommand);

            if (response.status() == ChannelResultStatus.SUCCEEDED) {
                refund.markSucceeded(response.channelRefundId(), null);
            } else if (response.status() == ChannelResultStatus.FAILED) {
                // 退款失败：释放占有的额度（markFailed 后不计入累计）
                refund.markFailed(response.message());
            } else {
                // UNKNOWN：保持 PENDING，占用额度，由查证补偿任务处理
                refund.markProcessing();
            }

            repository.save(order);
            outboxService.capture("PaymentOrder", order.id().value(), order.pullDomainEvents());
            return refund;
        } finally {
            lock.unlock();
        }
    }
}
