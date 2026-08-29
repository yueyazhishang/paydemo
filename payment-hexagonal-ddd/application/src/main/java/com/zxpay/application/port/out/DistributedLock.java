package com.zxpay.application.port.out;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * 出站端口：分布式锁。
 *
 * <p><b>什么时候不该用它？</b>
 *
 * <p>支付主链路的状态推进应尽量用<b>乐观锁</b>（聚合根上的 version），
 * 而不是分布式锁。原因：加锁会把并发串行化，
 * 而回调、查单补偿、商户关单这三条链路天然并发，
 * 一旦串行，高峰期回调堆积会直接拖垮整个支付链路。
 *
 * <p>乐观锁 + 状态机的组合能正确处理并发：
 * 冲突方重新加载最新状态，由状态机判断动作是否仍可执行，
 * 不可执行就安全放弃（例如订单已关闭，回调转为退款流程）。
 * 这比「排队加锁依次执行」正确得多——
 * 因为后到的请求本就不该覆盖先到的结果。
 *
 * <p>本端口只保留给<b>真正需要互斥</b>的场景：
 * <ul>
 *   <li>定时补偿任务的单例执行（避免多实例重复扫描）</li>
 *   <li>商户通知的投递去重</li>
 *   <li>通道侧 OAuth token 的刷新（防刷新风暴）</li>
 * </ul>
 */
public interface DistributedLock {

    /**
     * 尝试获取锁并执行。
     *
     * @param lockKey   锁键
     * @param leaseTime 租期。业务未执行完但租期到，锁会自动释放，避免死锁
     * @param action    获锁后执行的动作
     * @return 获锁执行返回其结果；未获锁返回空
     */
    <T> java.util.Optional<T> tryWithLock(String lockKey, Duration leaseTime, Supplier<T> action);

    /** 无需返回值的版本。 */
    default boolean tryWithLock(String lockKey, Duration leaseTime, Runnable action) {
        return tryWithLock(lockKey, leaseTime, () -> {
            action.run();
            return Boolean.TRUE;
        }).isPresent();
    }
}
