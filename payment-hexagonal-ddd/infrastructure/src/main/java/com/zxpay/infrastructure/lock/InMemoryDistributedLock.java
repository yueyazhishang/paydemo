package com.zxpay.infrastructure.lock;

import com.zxpay.application.port.out.DistributedLock;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 分布式锁的内存实现。
 *
 * <p>再次强调端口注释里的观点：<b>支付主链路的状态推进不该用锁</b>。
 *
 * <p>回调、查单补偿、商户关单三条链路天然并发，
 * 一旦加锁串行化，高峰期回调会堆积，整个支付链路被拖垮。
 * 正确姿势是聚合根上的<b>乐观锁 + 状态机</b>：
 * 冲突方重新加载最新状态，由状态机判断动作是否仍可执行，
 * 不可执行就安全放弃（例如订单已关闭，回调转为退款流程）。
 *
 * <p>本实现只服务于真正需要互斥的场景：
 * 定时补偿任务的单例执行、商户通知投递去重、
 * 以及 OAuth token 刷新（防并发刷新风暴）。
 */
@Component
public class InMemoryDistributedLock implements DistributedLock {

    private final Map<String, Instant> leases = new ConcurrentHashMap<>();

    @Override
    public <T> Optional<T> tryWithLock(String lockKey, Duration leaseTime, Supplier<T> action) {
        Instant now = Instant.now();
        Instant existing = leases.get(lockKey);

        // 租期已过则视为自动释放，避免持有者崩溃造成永久死锁
        if (existing != null && existing.isAfter(now)) {
            return Optional.empty();
        }

        leases.put(lockKey, now.plus(leaseTime));
        try {
            return Optional.ofNullable(action.get());
        } finally {
            leases.remove(lockKey);
        }
    }
}
