package com.zxpay.sharedkernel.model;

import com.zxpay.sharedkernel.event.DomainEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 聚合根基类。
 *
 * <p>承担两件事：
 * <ol>
 *   <li><b>领域事件收集</b>。聚合内部调用 {@link #registerEvent} 登记事实，
 *       由应用层在事务提交后统一发布。注意：绝不能在领域方法里直接发消息——
 *       一旦事务回滚，消息却已发出，会出现「钱没扣、下游却认为成功」的致命不一致。</li>
 *   <li><b>乐观锁版本号</b>。支付单会被「渠道回调」与「主动查单补偿」两条链路
 *       并发修改，没有版本号的 Update 会互相覆盖。这是支付系统高并发下最常见的
 *       丢状态原因。基础设施层必须实现 {@code WHERE id=? AND version=?} 并校验影响行数。</li>
 * </ol>
 *
 * @param <ID> 聚合标识类型
 */
public abstract class AggregateRoot<ID> {

    private final List<DomainEvent> domainEvents = new ArrayList<>();

    /** 乐观锁版本，由持久化层读写。 */
    private long version = 0L;

    public abstract ID id();

    protected void registerEvent(DomainEvent event) {
        if (event != null) {
            this.domainEvents.add(event);
        }
    }

    /** 返回不可变快照，供应用层发布。 */
    public List<DomainEvent> domainEvents() {
        return Collections.unmodifiableList(domainEvents);
    }

    /** 发布完成后清空，避免同一事件在长生命周期对象里被重复发布。 */
    public void clearDomainEvents() {
        this.domainEvents.clear();
    }

    public long version() {
        return version;
    }

    /** 仅供基础设施层在加载/落库时回写，业务代码不得调用。 */
    public void assignVersion(long version) {
        this.version = version;
    }
}
