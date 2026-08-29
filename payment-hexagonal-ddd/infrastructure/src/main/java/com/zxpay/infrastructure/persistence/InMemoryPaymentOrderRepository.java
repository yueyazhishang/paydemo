package com.zxpay.infrastructure.persistence;

import com.zxpay.domain.channel.model.ChannelCode;
import com.zxpay.domain.merchant.model.MerchantAppId;
import com.zxpay.domain.payment.model.PaymentOrder;
import com.zxpay.domain.payment.model.PaymentOrderId;
import com.zxpay.domain.payment.model.PaymentStatus;
import com.zxpay.domain.payment.port.PaymentOrderRepository;
import com.zxpay.sharedkernel.exception.ConcurrencyConflictException;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 支付单仓储的内存实现。
 *
 * <p>虽然是内存实现，但<b>乐观锁语义被完整保留</b>——这一点比用什么存储更重要。
 *
 * <p>支付单天然存在并发写：通道回调、查单补偿、商户关单三条链路
 * 可能同时命中同一笔订单。没有版本保护的 {@code UPDATE}
 * 会让后到的写覆盖先到的，造成「用户已付款、订单却显示失败」这类资金事故。
 *
 * <p>生产实现换成 MySQL 时，只需把这里的 version 判断换成
 * {@code UPDATE ... WHERE id = ? AND version = ?} 并校验影响行数，
 * 领域层代码一行不动——这就是端口抽象的意义。
 */
@Repository
public class InMemoryPaymentOrderRepository implements PaymentOrderRepository {

    private final Map<PaymentOrderId, PaymentOrder> store = new ConcurrentHashMap<>();

    /** 业务幂等索引：(appId, merchantOrderNo) → 支付单号。 */
    private final Map<String, PaymentOrderId> merchantOrderIndex = new ConcurrentHashMap<>();

    /** 通道交易号索引：回调定位用。 */
    private final Map<String, PaymentOrderId> transactionIndex = new ConcurrentHashMap<>();

    /** 通道订单号索引：回调定位用。与交易号分开建，因为回调可能只带其中之一。 */
    private final Map<String, PaymentOrderId> channelOrderIndex = new ConcurrentHashMap<>();

    @Override
    public Optional<PaymentOrder> findById(PaymentOrderId id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public Optional<PaymentOrder> findByMerchantOrderNo(MerchantAppId appId, String merchantOrderNo) {
        PaymentOrderId id = merchantOrderIndex.get(indexKey(appId, merchantOrderNo));
        return id == null ? Optional.empty() : findById(id);
    }

    @Override
    public Optional<PaymentOrder> findByChannelTransactionId(ChannelCode channel, String channelTransactionId) {
        PaymentOrderId id = transactionIndex.get(channel.name() + ":" + channelTransactionId);
        return id == null ? Optional.empty() : findById(id);
    }

    @Override
    public Optional<PaymentOrder> findByChannelOrderNo(ChannelCode channel, String channelOrderNo) {
        PaymentOrderId id = channelOrderIndex.get(channel.name() + ":" + channelOrderNo);
        return id == null ? Optional.empty() : findById(id);
    }

    /**
     * 保存，带乐观锁校验。
     *
     * @throws ConcurrencyConflictException 版本不匹配时抛出，调用方需重新加载后重试
     */
    @Override
    public synchronized void save(PaymentOrder order) {
        PaymentOrder existing = store.get(order.id());

        if (existing != null) {
            long expected = existing.version();
            if (expected != order.version()) {
                throw new ConcurrencyConflictException("PaymentOrder", order.id().value());
            }
        }

        long nextVersion = (existing == null ? 0L : existing.version()) + 1L;
        order.assignVersion(nextVersion);

        // 注意：这里绝不能调用 clearDomainEvents()。
        // 领域事件必须在事务提交之后由应用层发布并清理——
        // 若在仓储里提前清空，事件就永远发不出去了。
        store.put(order.id(), order);
        merchantOrderIndex.put(indexKey(order.appId(), order.merchantOrderNo()), order.id());

        if (order.currentChannel() != null) {
            String prefix = order.currentChannel().name() + ":";
            order.channelTransactionId().ifPresent(txn -> transactionIndex.put(prefix + txn, order.id()));
            order.currentAttempt().ifPresent(a -> channelOrderIndex.put(prefix + a.channelOrderNo(), order.id()));
        }
    }

    /**
     * 扫描长时间停留在中间态的订单，供补偿任务主动查单。
     *
     * <p>生产实现必须走「状态 + 更新时间」的联合索引，且必须分页。
     * 不分页的全表扫描在订单量上来后会直接打爆数据库。
     */
    @Override
    public List<PaymentOrder> findPendingBefore(List<PaymentStatus> statuses, Instant before, int limit) {
        List<PaymentOrder> result = new ArrayList<>();
        for (PaymentOrder order : store.values()) {
            if (result.size() >= limit) {
                break;
            }
            if (statuses.contains(order.status()) && order.updatedAt().isBefore(before)) {
                result.add(order);
            }
        }
        return result;
    }

    private String indexKey(MerchantAppId appId, String merchantOrderNo) {
        return appId.value() + ":" + merchantOrderNo;
    }
}
