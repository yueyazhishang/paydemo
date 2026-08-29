# 02 限界上下文与聚合设计

## 2.1 五个上下文

| 上下文 | 子域类型 | 核心聚合 | 为什么独立 |
|---|---|---|---|
| **payment** | 核心域 | `PaymentOrder` | 支付主链路，公司的钱从这里进来 |
| **refund** | 核心域 | `RefundOrder` | 逆向流程，规则复杂度不亚于正向 |
| **channel** | 支撑子域 | 能力矩阵（无聚合） | 通道差异的收口处 |
| **merchant** | 支撑子域 | `Merchant` | 商户配置与签约关系 |
| **notify** | 支撑子域 | 通知任务（无聚合） | 回调验签与商户投递 |

### 为什么 refund 是核心域而不是 payment 的一部分

很多团队把退款做成支付单上的一个状态字段 + 几个方法，理由是「退款就是支付的反操作」。这个判断在业务复杂度低时成立，在支付系统里不成立：

1. **规则密度不同**。退款有自己的窗口（国内 365 天 vs 海外 180 天）、次数限制（微信 50 次 vs 支付宝不限 vs Worldpay 只能退一次）、部分退款规则、结算后能否退款 —— 这些规则跟正向支付几乎没有交集。
2. **演进速度不同**。退款的运营需求（自动退款、批量退款、垫资退款、差额退款）变化远比正向支付快。
3. **失败语义不同**。支付失败了就是失败；退款失败可能要重试、可能要转人工、可能要换通道。

独立成上下文后，退款的规则变更不会波及支付主链路。

### channel 上下文为什么没有聚合根

它是**能力目录**，本质是配置数据 + 匹配规则，没有需要事务保护的状态变化。强行造一个聚合反而是过度设计。

它提供的是**领域服务**（`ChannelCapabilityRegistry`、`CapabilityMatcher`）和**端口**（`ChannelCapabilityQuery`、`ChannelHealthQuery`），而不是聚合。

## 2.2 聚合边界：两个关键判断

### 判断一：`PaymentAttempt` 在支付单聚合**内**

**理由：存在跨尝试的强不变量。**

> **同一时刻，同一通道只能有一个进行中的尝试。**

违反它会怎样？

```
线程 A：微信下单超时 → 判定失败 → 准备切到支付宝
线程 B：定时任务补偿 → 同通道重试 → 又向微信发一次下单
结果：微信侧产生了两笔交易（两次独立下单，幂等键不同）
```

注意：**幂等键只保护同键请求**。如果重试时新建了 attempt 并生成了新 key，通道会认为是两笔不同的交易。

要守住这条不变量，就必须在一个事务边界内修改 —— 所以尝试是聚合内部实体，随支付单一起加载和保存。

代码体现在 `PaymentOrder.beginAttempt()`：

```java
// 同一通道已有可重试的尝试 → 复用，保住原幂等键
Optional<PaymentAttempt> reusable = attempts.stream()
        .filter(a -> a.channel() == channel && a.canRetry())
        .findFirst();

if (reusable.isPresent()) {
    attempt = reusable.get();      // ← 复用，幂等键不变
    retryOfSameChannel = true;
} else {
    attempt = new PaymentAttempt(..., IdempotencyKeyFactory.channelPaymentKey(id, channel), ...);
    attempts.add(attempt);          // ← 换通道才新建
}
```

**重试复用 attempt，切换才新建 attempt** —— 这一行判断就是防重复扣款的核心。

### 判断二：`RefundOrder` 在支付单聚合**外**

**理由：不存在需要跨退款单例事务保证的不变量。**

退款的唯一约束是「累计退款不超过实付」。这条约束靠支付单上三个数值字段就够了：

```java
private Money paidAmount;       // 实付
private Money refundedAmount;   // 已退（成功）
private Money refundingAmount;  // 退款中（占用）
```

不需要把退款单装箱进支付单。

**如果强行内嵌会怎样？**

| 问题 | 后果 |
|---|---|
| 支付单随退款次数线性膨胀 | 一笔退 20 次的订单，聚合里有 20 条退款记录 |
| 每次退款都要加载整个聚合 | 加载成本随退款次数增长 |
| 并发的部分退款在聚合锁上串行化 | 三笔并发退款要排队，吞吐量降到 1/3 |
| **收益** | **零** —— 因为没有跨退款单的不变量需要保护 |

### 一句话总结

> **按不变量划边界，不按「看起来像父子关系」划边界。**

「退款属于支付」是从属关系，不是不变量关系。很多错误的聚合设计，根源就是把 ER 图的父子关系直接照搬成了聚合边界。

## 2.3 跨聚合一致性：预留 - 确认两段式

支付单和退款单是两个聚合，但退款必须保证不超额。做法：

```java
// 1. 占用（支付单聚合）
order.reserveRefund(amount, now);      // refundingAmount += amount
orderRepository.save(order);

// 2. 创建退款单（退款聚合）
RefundOrder refund = RefundOrder.create(...);
refundRepository.save(refund);

// 3. 提交通道
ChannelRefundResult result = port.refund(request);
refund.applyResult(result, now);

// 4. 确认或释放
if (refund.status() == SUCCEEDED) {
    order.applyRefundSucceeded(amount, now);    // refundingAmount -= amount; refundedAmount += amount
} else if (refund.status() == FAILED) {
    order.applyRefundFailed(amount, now);       // refundingAmount -= amount
}
// PROCESSING 保持占用，等通知或查单推进
```

**关键点**：

1. **占用与释放必须成对**。并发的两笔部分退款都先占用，谁超额谁在 `reserveRefund` 就被拒绝（抛 `REFUND_AMOUNT_EXCEEDED`），不会等到通道报错才发现。
2. **不需要分布式事务**。两个聚合在同一个数据库、同一个事务中，直接就一致了。
3. **没有最终一致的窗口期**。这是相对 Saga 方案最大的优势。

> **DDD 实践建议**：处理跨聚合一致性时，**先问「能不能放进一个事务」**。只有确实跨库跨服务时才考虑 Saga。很多团队一上来就上 Saga，把简单问题复杂化了 —— Saga 的补偿逻辑、幂等、悬挂问题，每一个都比「一个事务」难十倍。

## 2.4 聚合设计的几条经验规则

### 规则一：聚合内强一致，聚合间最终一致（但能一个事务就别拆）

本工程里 payment 与 refund 恰好在同一库，所以用事务。如果退款服务独立部署、独立数据库，就必须改成：发 `RefundRequested` 事件 → 退款服务消费 → 回发 `RefundSucceeded` 事件 → 支付服务更新累计退款。

### 规则二：聚合要小

小聚合的好处：加载快、锁竞争少、事务短。

判断聚合是否过大的信号：
- 加载一个聚合要查 5 张以上的表
- 一个事务里经常因为「不相关的字段被改」而冲突
- 大部分操作只用得到聚合的一小部分数据

`RefundOrder` 独立出来的本质原因就是这个。

### 规则三：聚合之间只通过 ID 引用

```java
public final class RefundOrder {
    private final PaymentOrderId paymentOrderId;   // ← ID，不是 PaymentOrder 对象
    private final Money originalAmount;            // ← 快照，不是实时引用
}
```

**不要**在 `RefundOrder` 里放 `PaymentOrder order` 对象引用。后果：加载退款单会把支付单整个拉进来，两个聚合的生命周期被绑死，事务边界模糊。

`originalAmount` 是**快照**而非实时引用 —— 退款金额校验用支付时的原额，而不是当前支付单的金额（后者可能因其他操作变化）。

### 规则四：跨聚合的数据冗余要有明确的同步机制

支付单上冗余了 `refundedAmount`，这是有意为之（避免每次校验都查全量退款单）。同步机制就是上面的预留 - 确认两段式，在同一事务里完成。

**冗余字段必须有且只有一个写入方**，否则就会出现「两处数据不一致，不知道该信谁」。本工程中 `refundedAmount` 只由退款链路写入。

## 2.5 值对象的设计

### Money：支付领域最重要的值对象

```java
public final class Money implements Comparable<Money> {
    private final BigDecimal amount;
    private final Currency currency;
}
```

四条铁律：

1. **不可变**。所有运算返回新实例。
2. **永不用 double 构造**。只接受 String / BigDecimal / 最小单位 long，避免 `0.1` 这类二进制浮点误差进入资金链路。
3. **精度由币种决定**。`Money.ofMinor(100, JPY)` = 100 日元；`Money.ofMinor(100, USD)` = 1.00 美元。差异全部由 `Currency.minorUnits()` 承担。
4. **跨币种运算直接抛异常**。人民币加美元是业务错误，不是技术问题，必须在值对象层面拦住，而不是留给下游对账发现。

> **零小数位币种是跨境支付的经典事故源**。JPY / KRW / VND 是 0 位，BHD / KWD 是 3 位。统一按 2 位处理，日元就会被放大 100 倍。因此**业务代码里严禁出现 `amount * 100`**，一律走 `Money.minorUnits()` / `Money.ofMinor()`。

### 其他值对象

| 值对象 | 承载的不变量 |
|---|---|
| `Authorization` | 授权金额、有效期（默认 7 天）、请款不得超额 |
| `ChannelRawStatus` | 原始状态与归一化状态双轨保留 |
| `FailureInfo` | `retryable` / `switchable` / `requiresQueryBeforeDecision` |
| `ChannelInteraction` | 前端唤起参数（二维码 / 跳转 / SDK 参数），消灭上层 if-else |
| `RefundPolicy` | 退款窗口、次数、是否即时、是否需证书 |

## 2.6 领域事件

集中在 `PaymentEvents` / `RefundEvents` 两个容器类里。设计原则：

1. **自包含**。消费方拿到事件后不需要回查支付服务就能处理，因此金额、通道、商户号全部内联。
2. **只陈述事实**。名称一律过去式（`PaymentSucceeded`，不是 `PaymentSuccess`）。
3. **不携带领域对象**。事件要跨进程传播，携带 `PaymentOrder` 会导致序列化后语义漂移。
4. **事务提交后才发布**。这一点由应用层保证 —— **先落库、再发消息**。反过来就会「库里没成功、下游已通知商户发货」。

### 事件清单

**支付**：`PaymentOrderCreated` / `PaymentRouted` / `PaymentAttemptStarted` / `PaymentSucceeded` / `PaymentFailed` / `ChannelSwitched` / `PaymentAuthorized` / `CaptureRequested` / `PaymentCaptured` / `PaymentClosed`

**退款**：`RefundOrderCreated` / `RefundSucceeded` / `RefundFailed`

其中两个值得特别说明：

- **`PaymentAttemptStarted`** 带 `retryOfSameChannel` 标志。同通道重试与切换通道在统计口径上必须区分，否则算出来的通道成功率是错的。
- **`PaymentAuthorized`** 订阅方包括授权到期提醒。授权 7 天过期，**必须提醒商户请款** —— 酒店预授权是最典型的踩坑场景：用户住完店，商户请款时授权已失效。
