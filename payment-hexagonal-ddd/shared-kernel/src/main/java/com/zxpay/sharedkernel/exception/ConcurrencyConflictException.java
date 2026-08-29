package com.zxpay.sharedkernel.exception;

/**
 * 并发冲突异常：乐观锁更新影响行数为 0 时由基础设施层抛出。
 *
 * <p>支付单上存在天然并发源：
 * <ul>
 *   <li>通道异步回调（用户支付成功，微信推 notify）</li>
 *   <li>定时任务主动查单补偿（回调丢失时兜底）</li>
 *   <li>商户主动关单 / 用户取消</li>
 * </ul>
 * 三者可能同时命中同一笔订单。正确做法不是加锁串行化，而是乐观锁 + 有限重试：
 * 冲突方重新加载最新状态，由状态机判断当前动作是否仍可执行，不可执行则安全放弃。
 *
 * <p>典型例子：回调要置为 SUCCESS，关单要置为 CLOSED。若关单先成功，
 * 回调重试后加载到的状态是 CLOSED，状态机判定终态不可逆，回调转为「退款原路退回」流程，
 * 而不是强行覆盖。
 */
public class ConcurrencyConflictException extends DomainException {

    private final String aggregateType;
    private final String aggregateId;

    public ConcurrencyConflictException(String aggregateType, String aggregateId) {
        super("CONCURRENCY_CONFLICT",
                "concurrent modification detected on " + aggregateType + "[" + aggregateId + "], please reload and retry");
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
    }

    public String aggregateType() {
        return aggregateType;
    }

    public String aggregateId() {
        return aggregateId;
    }
}
