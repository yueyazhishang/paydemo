# 05 幂等与并发控制

## 5.1 三层幂等

支付系统里幂等不是「加个唯一索引」就完事，而是三层，每层防的东西不同：

| 层次 | 机制 | 防的是什么 | 失效后果 |
|---|---|---|---|
| **接口层** | `Idempotency-Key` 请求头 | 商户重试导致重复下单 | 用户付一次钱，产生两笔订单 |
| **业务层** | `(app_id, merchant_order_no)` 唯一索引 | 同一笔业务重复生成支付单 | 同上，且商户对不上账 |
| **通道层** | 确定性生成的幂等键 | 我方重试导致通道重复扣款 | **用户被扣两次钱** |

第三层最致命，也最容易被忽略。

### 为什么业务层唯一索引是 `(app_id, merchant_order_no)` 而不是单字段

不同商户完全可以都用 `"ORDER_001"` 这种编号。只按单号做唯一，会直接串单 —— A 商户的下单请求命中了 B 商户的订单。

## 5.2 通道幂等键：为什么不能用 UUID

```java
// ❌ 错误做法
String key = UUID.randomUUID().toString();
attempt.setIdempotencyKey(key);
channelPort.pay(request);      // ← 进程在这里崩溃
// 恢复后重试：又生成一个新 key → 通道视为全新交易 → 重复扣款
```

**生成 key、持久化、调用通道三步不是原子的**，进程随时可能在中间挂掉。用随机 key，一旦丢失就永远找不回来，重试必然产生第二笔交易。

### 正确做法：确定性推导

```java
public static String channelPaymentKey(PaymentOrderId orderId, ChannelCode channel) {
    return "pay:" + orderId.value() + ":" + channel.name();
}
```

同一个（订单，通道）组合，无论在哪台机器、第几次计算，得到的 key 完全相同。崩溃后重试算出来的还是同一个 key，通道正确识别为重复请求并返回原结果。

> **注意这里不带尝试序号**：同一通道的重试必须复用同一个 key，这是幂等的本意。序号只在「同一通道真的要发起一笔全新交易」时才需要，而那种情况在本模型中不会发生 —— **重试复用 attempt，切换换的是另一家通道**。

### 请款键要带序号

```java
public static String captureKey(PaymentOrderId orderId, int captureSeq) {
    return "cap:" + orderId.value() + ":" + captureSeq;
}
```

一笔授权可能分多次部分请款，每次的键必须不同。否则第二次请款会被当成第一次的重复请求，**返回第一次的结果**（金额不对）。

### 不要用 hashCode()

```java
public static String digest(String businessKey, int length) {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    // 取前 N 位十六进制
}
```

`hashCode()` 的算法在不同 JDK 版本间不保证稳定，且碰撞概率远高于 SHA-256。**碰撞意味着两笔不同交易共用一个幂等键，后果是其中一笔被静默吞掉。**

## 5.3 重试 vs 切换：保住幂等键的关键

```java
public PaymentAttempt beginAttempt(ChannelCode channel, Instant now) {
    boolean switching = currentChannel != null && currentChannel != channel;

    Optional<PaymentAttempt> reusable = attempts.stream()
            .filter(a -> a.channel() == channel && a.canRetry())
            .findFirst();

    if (reusable.isPresent()) {
        attempt = reusable.get();          // ← 复用，幂等键不变，重试安全
        retryOfSameChannel = true;
    } else {
        if (switching) {
            switchChannel(channel, "...", now);   // 旧尝试标记 SWITCHED_OUT，记录保留
        }
        attempt = new PaymentAttempt(..., IdempotencyKeyFactory.channelPaymentKey(id, channel), ...);
        attempts.add(attempt);              // ← 换通道才新建
        retryOfSameChannel = false;
    }
    ...
}
```

**规则：**
- **重试** = 同一通道再试一次 → **复用**当前 attempt（幂等键不变）
- **切换** = 换一家通道 → **新建** attempt，旧 attempt 标记 `SWITCHED_OUT` 但**记录完整保留**

### 为什么失败的尝试记录不能删

A 通道下单超时（UNKNOWN），切到 B 通道支付成功。事后对账发现 A 通道其实也扣了款。

**如果没有这次尝试的记录，这笔悬空扣款就永远找不回来。**

这就是 `PaymentAttempt` 存在的第三个理由（前两个是保住幂等键、支撑通道质量分析）。

## 5.4 乐观锁：支付单并发的正确答案

### 并发从哪来

支付单上存在三个天然并发源：

1. **通道异步回调** —— 用户支付成功，微信推 notify
2. **定时任务主动查单** —— 回调丢失时兜底
3. **商户主动关单 / 用户取消**

三者可能同时命中同一笔订单。

### 为什么不用分布式锁

加锁会把并发串行化。高峰期回调堆积，整个支付链路被拖垮。

更根本的问题是：**加锁只解决了「同时改」的问题，没解决「谁该赢」的问题。**

考虑这个场景：

```
订单已支付成功（SUCCEEDED）
同时到达：① 重复的支付成功回调  ② 商户的关单请求
```

加锁串行化后，谁先执行谁赢 —— 如果关单请求先拿到锁，已支付的订单就被关掉了。

### 乐观锁 + 状态机：让「谁该赢」有明确答案

```java
// 1. 冲突方重新加载最新状态
// 2. 由状态机判断当前动作是否仍可执行
PaymentStateMachine.requireTransition(PaymentStatus.SUCCEEDED, PaymentStatus.CLOSED);
// → 抛 PAYMENT_STATUS_TRANSITION_ILLEGAL，关单请求被安全拒绝
```

关单请求不是「被阻塞等待」，而是**被判定为不合法并安全放弃** —— 因为它本就不该覆盖已支付的状态。

```java
@Override
public synchronized void save(PaymentOrder order) {
    PaymentOrder existing = store.get(order.id());
    if (existing != null && existing.version() != order.version()) {
        throw new ConcurrencyConflictException("PaymentOrder", order.id().value());
    }
    order.assignVersion((existing == null ? 0L : existing.version()) + 1L);
    store.put(order.id(), order);
}
```

生产实现换成 MySQL：

```sql
UPDATE payment_order SET ..., version = version + 1
WHERE id = ? AND version = ?
-- 影响行数为 0 → 抛 ConcurrencyConflictException
```

**领域层代码一行不动** —— 这就是端口抽象的价值。

### 聚合根上的版本号

```java
public abstract class AggregateRoot<ID> {
    private long version = 0L;   // 乐观锁版本，由持久化层读写
}
```

基础设施层在加载时回写版本，在保存时校验并递增。

## 5.5 分布式锁：只用在真正需要的地方

`DistributedLock` 端口保留了，但**支付主链路不用它**。只服务三个场景：

| 场景 | 为什么需要锁 |
|---|---|
| 定时补偿任务的单例执行 | 避免多实例重复扫描同一批订单 |
| 商户通知的投递去重 | 避免同一条通知被并发投递多次 |
| OAuth token 刷新 | **防刷新风暴**：token 过期时，并发的 100 个请求不应该同时去刷新 |

### OAuth token 刷新风暴

PayPal、Worldpay 用 `OAUTH2_CLIENT` 鉴权。token 过期时，若 100 个并发请求各自去刷新，会把授权服务打爆。

正确做法：加分布式锁，只有第一个请求去刷新，其余等待并复用新 token。

```java
public enum AuthModel {
    OAUTH2_CLIENT("OAuth2 客户端凭证", false, true),
    //                                  ↑      ↑
    //                        不需要逐笔签名   凭据带过期时间
}
```

`expiringCredential = true` 就是提醒实现者：这个通道需要缓存 + 并发刷新保护。

## 5.6 接口层幂等的实现要点

```java
// 1. 尝试占用
if (!idempotencyStore.tryAcquire(key, ttl)) {
    // 已被占用 → 说明有并发的相同请求正在处理
    return findExistingResult(key);      // 或返回"处理中"
}

// 2. 处理业务
...

// 3. 保存结果快照
idempotencyStore.saveResult(key, orderId, Duration.ofHours(24));
```

### 要点一：判断与写入必须原子

分成两步做，两个并发的同键请求会**同时通过检查**，幂等直接失效。

Redis 实现要用 `SETNX` 或 Lua 脚本。

### 要点二：要保存首次处理的结果快照

重复请求直接返回快照。若第二次返回不同的订单号，商户侧照样会乱 ——

> **「幂等」不只是「不重复执行」，还包括「返回结果一致」。**

### 要点三：分布式环境下不能用内存实现

商户的重试请求很可能落到另一台机器上，本地 `ConcurrentHashMap` 拦不住。

## 5.7 并发退款的金额守恒

三笔并发的部分退款，总额超过实付 —— 怎么防？

```java
public void reserveRefund(Money amount, Instant now) {
    if (amount.isGreaterThan(remainingRefundable())) {
        throw new DomainException("REFUND_AMOUNT_EXCEEDED", ...);
    }
    this.refundingAmount = refundingAmount.plus(amount);   // 占用
    transitionTo(PaymentStatus.REFUNDING, now);
}

public Money remainingRefundable() {
    return paidAmount.minus(refundedAmount).minus(refundingAmount);   // 实付 - 已退 - 退款中
}
```

**预留 - 确认两段式**：

```
时刻 T：实付 100，已退 0，退款中 0 → 可退 100
  ├─ 退款 A（60）：占用 → 可退 40
  ├─ 退款 B（30）：占用 → 可退 10
  └─ 退款 C（20）：20 > 10 → 直接拒绝，不会打到通道
```

三个数值字段 + 乐观锁，就够了。**不需要分布式事务，也不需要 Saga** —— 因为支付单与退款单在同一个数据库、同一个事务里。

## 5.8 检查清单

- [ ] 通道幂等键是否由领域层确定性生成并持久化？（不能随机生成）
- [ ] 适配器是否原样使用了传入的 `idempotencyKey`？（不能自行生成）
- [ ] 重试是否复用了同一个 `PaymentAttempt`？（保住幂等键）
- [ ] 失败的尝试记录是否完整保留？（对账需要）
- [ ] 仓储的 `save` 是否做了乐观锁校验？（影响行数为 0 要抛异常）
- [ ] 仓储的 `save` 是否**没有**清空领域事件？（事件要在事务提交后由应用层发布）
- [ ] 支付主链路是否避免了分布式锁？（改用乐观锁 + 状态机）
- [ ] 接口幂等的「判断 + 写入」是否原子？
- [ ] 幂等结果快照是否保存并在重复请求时返回？
