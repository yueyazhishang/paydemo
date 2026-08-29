package com.zx.payment.acquisition.domain.model;

/**
 * 乐观锁并发冲突。仓储 CAS 更新影响行数为 0 时抛出。
 *
 * 语义：你手上的这份聚合快照已经过期，请重新加载后再试。
 * 应用层对此的标准处理是【有限次重试】（通常 3 次），而不是直接返回失败——
 * 因为多数冲突是"通道回调与主动查单撞车"这种良性竞争，重试一次就能收敛。
 */
public class OptimisticConcurrencyException extends RuntimeException {

    private final String aggregateId;
    private final int expectedVersion;

    public OptimisticConcurrencyException(String aggregateId, int expectedVersion) {
        super(String.format("聚合[%s]并发冲突，期望版本 %d 已失效，请重试", aggregateId, expectedVersion));
        this.aggregateId = aggregateId;
        this.expectedVersion = expectedVersion;
    }

    public String aggregateId() { return aggregateId; }
    public int expectedVersion() { return expectedVersion; }
}
