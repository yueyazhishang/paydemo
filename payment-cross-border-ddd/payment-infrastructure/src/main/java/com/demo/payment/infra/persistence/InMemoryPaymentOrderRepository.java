package com.demo.payment.infra.persistence;

import com.demo.payment.domain.acquiring.model.aggregate.PaymentOrder;
import com.demo.payment.domain.acquiring.model.aggregate.PaymentOrderId;
import com.demo.payment.domain.acquiring.model.vo.OutTradeNo;
import com.demo.payment.domain.acquiring.repository.PaymentOrderRepository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 支付单仓储的内存实现（演示用）。
 *
 * <p><b>它演示了三件生产环境必须做的事：</b>
 * <ol>
 *   <li><b>乐观锁</b>：{@code version} 不匹配时抛异常，防止并发覆盖。
 *       真实实现是 {@code UPDATE ... WHERE id=? AND version=?}，
 *       影响行数为 0 即抛并发异常。</li>
 *   <li><b>商户订单号唯一性</b>：用 {@code merchantId + "#" + merchantOrderNo} 建索引，
 *       这是防重复下单的最后兜底 —— 即使上层幂等全部失效，这里也能挡住。</li>
 *   <li><b>分布式锁</b>：真实环境用 Redis（Redisson），这里用本地锁演示语义。</li>
 * </ol>
 *
 * <p><b>生产替换指引：</b>把三个 Map 换成 MyBatis/JPA 的 Mapper 调用，
 * 把 {@code new ReentrantLock()} 换成 Redisson 的 {@code getLock("payment:order:" + id)}，
 * 其余代码无需改动 —— 这就是依赖倒置的价值。
 */
@org.springframework.stereotype.Repository
public class InMemoryPaymentOrderRepository implements PaymentOrderRepository {

    private final Map<String, PaymentOrder> store = new ConcurrentHashMap<>();
    /** 商户订单号索引：merchantId + "#" + merchantOrderNo → paymentOrderId */
    private final Map<String, String> merchantOrderIndex = new ConcurrentHashMap<>();
    /** 通道订单号索引：outTradeNo → paymentOrderId（回调时反查用） */
    private final Map<String, String> outTradeNoIndex = new ConcurrentHashMap<>();
    private final Map<String, Lock> locks = new ConcurrentHashMap<>();

    @Override
    public Optional<PaymentOrder> findById(PaymentOrderId id) {
        return Optional.ofNullable(store.get(id.value()));
    }

    @Override
    public Optional<PaymentOrder> findByMerchantOrderNo(String merchantId, String merchantOrderNo) {
        String pid = merchantOrderIndex.get(merchantId + "#" + merchantOrderNo);
        return pid == null ? Optional.empty() : Optional.ofNullable(store.get(pid));
    }

    @Override
    public Optional<PaymentOrder> findByOutTradeNo(OutTradeNo outTradeNo) {
        String pid = outTradeNoIndex.get(outTradeNo.value());
        return pid == null ? Optional.empty() : Optional.ofNullable(store.get(pid));
    }

    @Override
    public synchronized void save(PaymentOrder order) {
        // 乐观锁校验：新单 version=0，已存在的单必须版本递增
        PaymentOrder existing = store.get(order.id().value());
        if (existing != null && existing.version() != order.version()) {
            throw new IllegalStateException("乐观锁冲突，订单已被其他请求修改: "
                    + order.id().value() + " 期望版本=" + existing.version()
                    + " 实际版本=" + order.version());
        }

        store.put(order.id().value(), order);
        merchantOrderIndex.put(order.merchantId() + "#" + order.merchantOrderNo(), order.id().value());
        for (var attempt : order.attempts()) {
            outTradeNoIndex.put(attempt.outTradeNo().value(), order.id().value());
        }
    }

    @Override
    public List<PaymentOrder> findTimeoutCandidates(int limitMinutes, int limit) {
        Instant threshold = Instant.now().minus(limitMinutes, ChronoUnit.MINUTES);
        return store.values().stream()
                .filter(o -> o.status().isProcessing())
                .filter(o -> o.createdAt().isBefore(threshold))
                .limit(limit)
                .toList();
    }

    @Override
    public Lock obtainLock(PaymentOrderId id) {
        return locks.computeIfAbsent(id.value(), k -> new ReentrantLock());
    }
}
