package com.zx.payment.shared;

import java.time.Instant;

/**
 * 集成事件标记接口：跨限界上下文的【发布语言（Published Language）】。
 *
 * 契约规则（任何一条违反都会导致上下文耦合）：
 *  1. 只读——下游只能读，不能改，更不能反向写入上游；
 *  2. 版本化——类名带版本号（V1），字段只增不改，破坏性变更新开 V2 并双写过渡；
 *  3. 自包含——下游拿到这一份数据就能办事，不需要回头回调上游；
 *  4. 无领域对象——只准带 String / long / Instant / 枚举这类原始类型，
 *     绝不能带 Payment、Refund 这种聚合。带了就等于把上游模型泄漏给下游。
 *
 * 为什么用接口而不是抽象类：集成事件是"契约声明"，不需要公共实现。
 * 各上下文自己定义 record 实现它，编译期即可校验契约完整性。
 */
public interface IntegrationEvent {

    String eventId();

    Instant occurredAt();

    /** 事件类型标识，用于消息中间件的路由与消费者识别。 */
    default String eventType() {
        return getClass().getSimpleName();
    }
}
