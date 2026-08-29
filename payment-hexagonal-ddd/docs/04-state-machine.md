# 04 状态机与生命周期

## 4.1 为什么状态机必须集中管理

常见写法是把状态转移散落在各处：

```java
// PaymentServiceImpl
if (order.getStatus() == PAYING) { order.setStatus(SUCCEEDED); }

// ChannelNotifyController
if (order.getStatus() != SUCCEEDED) { order.setStatus(SUCCEEDED); }

// CompensationJob
if (order.getStatus() == PAYING) { order.setStatus(FAILED); }
```

三处判断，三个入口，规则各不相同。三个月后没人说得清「已关闭的订单到底能不能被改成成功」。

集中管理的三个收益：

1. **可审计**。一张表看完哪些转移允许、哪些禁止。Code Review 时能一眼发现「已支付订单竟然允许被关闭」这类致命漏洞。
2. **天然防御乱序通知**。非法转移直接被拒，而不是把已成功的订单覆盖成失败。
3. **终态不可逆**。从机制上杜绝「用户已付款、订单却被关掉」。

## 4.2 合法转移表

```
CREATED ──▶ ROUTING ──▶ PAYING ──┬──▶ AUTHORIZED ──▶ CAPTURING ──▶ SUCCEEDED
    │           │         │      │         │              │             │
    │           │         │      └──▶ USERPAYING          │             ▼
    │           │         │                               │        REFUNDING
    │           │         └──▶ FAILED（终态）              │             │
    │           │                                         │             ▼
    │           └──▶ FAILED / CLOSED                      │      PARTIAL_REFUNDED
    │                                                     │             │
    └──▶ CLOSED（终态）                                    │             ▼
                                                          └──▶ CLOSED   REFUNDED（终态）
```

完整定义：

| 从 | 允许转移到 | 说明 |
|---|---|---|
| `CREATED` | `ROUTING`、`CLOSED` | 商户在路由前主动关单 |
| `ROUTING` | `PAYING`、`FAILED`、`CLOSED` | `FAILED` 表示无可用通道 |
| `PAYING` | `USERPAYING`、`AUTHORIZED`、`SUCCEEDED`、`FAILED`、`CLOSED` | `CLOSED` 为超时未支付 |
| `USERPAYING` | `PAYING`、`SUCCEEDED`、`FAILED`、`CLOSED` | `PAYING` 表示用户取消确认 |
| `AUTHORIZED` | `CAPTURING`、`SUCCEEDED`、`FAILED`、`CLOSED` | `CLOSED` 为撤销授权后关闭 |
| `CAPTURING` | `SUCCEEDED`、`FAILED`、`AUTHORIZED` | 请款失败但未超授权期，可重新请款 |
| `SUCCEEDED` | `REFUNDING`、`PARTIAL_REFUNDED` | **不能转移到 `CLOSED`** |
| `REFUNDING` | `SUCCEEDED`、`PARTIAL_REFUNDED`、`REFUNDED` | 退款失败退回已支付态 |
| `PARTIAL_REFUNDED` | `REFUNDING`、`REFUNDED` | 继续退剩余金额 |
| `FAILED` / `CLOSED` / `REFUNDED` | — | **终态，不允许任何转移** |

### 三条关键约束

**1. `SUCCEEDED → CLOSED` 不在表中**

已支付的订单要终止必须走退款，不能关闭。否则形成**账务黑洞**：钱收了，订单关了，既不发货也不退款。

```java
public void close(String reason, Instant now) {
    if (status.isPaid()) {
        throw new DomainException("PAID_ORDER_CANNOT_BE_CLOSED",
            "paid order cannot be closed directly, use refund instead: " + id.value());
    }
    transitionTo(PaymentStatus.CLOSED, now);
    ...
}
```

注意 `PaymentStateMachine` 本身也会拦截这条转移 —— **聚合内的显式判断 + 状态机的兜底校验，双保险**。前者给出可读的错误信息，后者保证无论从哪个入口进来都绕不过去。

**2. `from == to` 视为合法（幂等）**

```java
public static boolean canTransit(PaymentStatus from, PaymentStatus to) {
    if (from == to) {
        return true;   // 重复应用同一状态不算非法
    }
    return TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
}
```

通道重复投递同一条通知时，重复应用相同状态不应报错，否则重试通知会刷出大量异常。

**3. `SUCCEEDED` 不是终态**

因为它还可以退款。真正的终态只有 `FAILED` / `CLOSED` / `REFUNDED`。

这一点在补偿扫描时很重要：`findPendingBefore` 扫描的是**中间态**（`ROUTING`/`PAYING`/`USERPAYING`/`AUTHORIZED`/`CAPTURING`），不是「非终态」。

## 4.3 乱序通知的处理

通道通知会重复、会乱序。网络重放、重试、多实例消费都可能让「成功」通知晚于「失败」通知到达。

两层防御：

### 第一层：状态机

非法转移直接被拒。订单已 `SUCCEEDED`，迟到的 `FAILED` 通知不会生效。

### 第二层：事件时间戳守卫

```java
private boolean isStaleNotification(PaymentOrder order, NotificationPayload payload, Instant receivedAt) {
    Instant eventTime = payload.effectiveEventTime(receivedAt);
    if (eventTime == null) return false;
    return order.status().isTerminal() && eventTime.isBefore(order.updatedAt());
}
```

通知的通道侧时间早于订单最后更新时间，且订单已处于终态 → 丢弃。

> 这就是为什么 `NotificationPayload` 必须有 `eventTime` 字段。缺少它，就只能靠「先到先得」，在重放场景下会出错。

### 结果分类：为什么不用抛异常

`applyChannelResult` 返回 `ChannelResultApplication` 而不是抛异常：

| 结果 | 含义 | 后续动作 |
|---|---|---|
| `APPLIED` | 状态已正常推进 | 无 |
| `IGNORED_DUPLICATE` | 重复通知，幂等命中 | 无 |
| `IGNORED_TERMINAL` | 终态，本次结果不影响状态 | 无 |
| **`TERMINAL_CONFLICT_PAID_AFTER_CLOSE`** | **订单已关闭但通道侧支付成功** | **必须触发自动退款** |
| `AMOUNT_MISMATCH` | 实付金额与订单金额不符 | 人工核对 |
| `UNKNOWN_NEEDS_QUERY` | 结果未知 | 主动查单 |

**为什么回调场景不能抛异常？**

回调接口返回 5xx，通道会按重试策略反复推送，同一笔问题被放大 15 次，告警淹没一切，而问题本身一点没解决。

正确做法：**先收下、再分类**。接口一律快速返回成功，把「处理不了」的情况沉淀成状态，交给补偿任务或人工处理。

## 4.4 `TERMINAL_CONFLICT_PAID_AFTER_CLOSE`：最危险的场景

订单已关闭（超时或商户取消），但通道通知说用户付款成功了。

这意味着：**钱已经进了我们的账，订单却是关闭状态** —— 既不会发货也不会退款，钱凭空消失在账务里。

```
用户扫码 ──▶ 订单创建（状态 PAYING）
             │
             ├─▶ 用户犹豫，5 分钟后才付款
             │
             ├─▶ 我们的超时任务把订单置为 CLOSED
             │
             └─▶ 通道推送「支付成功」通知
                  ↓
            状态机拒绝（CLOSED 是终态）
                  ↓
            返回 TERMINAL_CONFLICT_PAID_AFTER_CLOSE
                  ↓
            【必须触发自动原路退款】
```

代码里目前只打了 CRITICAL 日志。**生产必须接退款流程**并触发人工跟进。

> 这类冲突在超时窗口设置不当时会批量出现。如果通道侧的订单有效期（微信 2 小时）长于我们的订单超时时间（比如 15 分钟），就会有一批订单在关闭后被支付成功。**两边的时间必须对齐**，这是设计约束不是实现细节。

## 4.5 超时与补偿

### 订单超时

```java
public boolean expireIfNeeded(Instant now) {
    if (!isExpired(now)) return false;
    if (status.isPaid()) return false;      // 已支付的不会被超时误伤
    transitionTo(PaymentStatus.CLOSED, now);
    registerEvent(new PaymentEvents.PaymentClosed(..., "EXPIRED"));
    return true;
}
```

已支付的订单不会被超时误伤 —— 状态机保证了这一点。

### 补偿扫描

```java
public int compensatePendingPayments() {
    Instant threshold = ClockHolder.now().minus(Duration.ofMinutes(3));
    List<PaymentOrder> pending = orderRepository.findPendingBefore(pendingStatuses, threshold, 200);

    int processed = 0;
    for (PaymentOrder order : pending) {
        try {
            queryAndSync(order.id());      // 单笔失败不能中断整批
            processed++;
        } catch (Exception e) {
            log.error(...);
        }
    }
    return processed;
}
```

三个设计点：

1. **阈值 3 分钟**。太短会与正常支付流程冲突（用户还在输入密码），太长会让用户干等。
2. **分页 200 条**。生产必须分页，不分页的全表扫描会打爆数据库。
3. **单笔失败不中断**。一笔脏数据卡死所有补偿，是补偿任务最常见的设计缺陷。

### 为什么必须有主动查单

见 [03 章通知规范表格](03-channel-capability-matrix.md#37-通知规范)：**所有通道的通知都会丢**，Stripe 只重试 3 次。

只依赖通知，「用户已付款、订单仍显示待支付」是**必然结果**，而不是偶发故障。

## 4.6 失败处理的决策树

收到失败结果时，按 `FailureInfo` 的三个属性决定动作：

```
                    ┌─────────────────────┐
                    │   收到失败结果       │
                    └──────────┬──────────┘
                               │
              ┌────────────────┼────────────────┐
              ▼                ▼                ▼
    requiresQueryBefore  switchable      retryable
         Decision?          ?                ?
              │                │                │
         true │           true │           true │
              ▼                ▼                ▼
      【先查单】           【换通道】        【同通道重试】
      绝不提前置失败     能力对等才切       复用 attempt
                          风控不切          保住幂等键
```

| 类别 | `retryable` | `switchable` | `requiresQueryBeforeDecision` | 处置 |
|---|---|---|---|---|
| `BUSINESS`（余额不足） | ❌ | ✅ | ❌ | 直接失败，不重试 |
| `RISK`（风控拦截） | ❌ | ❌ | ❌ | 直接失败，**不换通道** |
| `INVALID_REQUEST` | ❌ | ❌ | ❌ | 我方 bug 或配置缺失，告警 |
| `CHANNEL_UNAVAILABLE` | ✅ | ✅ | ❌ | 可重试、可切换 |
| `CHANNEL_MAINTENANCE` | ✅ | ✅ | ❌ | 延后重试或切换 |
| `IDEMPOTENCY_CONFLICT` | ❌ | ❌ | ✅ | 先查单确认 |
| **`UNKNOWN`（超时）** | ✅ | ❌ | ✅ | **先查单，绝不提前置失败** |

### 两个最容易做错的地方

**1. 超时不能判失败**

调用通道超时，不代表用户没被扣款 —— 很可能通道已经扣款成功，只是响应没回到我们这里。

直接置为 `FAILED`，就会出现**「用户付了钱，订单是失败的」**，这是支付系统最严重的事故类型之一。

```java
// 适配器必须这样处理超时
case TIMEOUT -> ChannelResult.failed(...,
    FailureInfo.unknown(channel + "_TIMEOUT", "调用通道超时，结果未知，必须查单确认"), ...);
```

聚合根收到后：

```java
if (failure.requiresQueryBeforeDecision()) {
    return ChannelResultApplication.UNKNOWN_NEEDS_QUERY;   // 保持中间态，不落终态
}
```

**2. 风控拦截不能换通道**

```java
public static FailureInfo risk(String code, String message) {
    return new FailureInfo(code, message, FailureCategory.RISK, false, false);
    //                                                          ↑      ↑
    //                                                     不重试   不切换
}
```

风控拦截换哪家都会被拦。切了只是把拒绝率平摊到别的通道上，还会**污染健康度指标** —— 让路由以为那家通道有问题。

```java
private boolean shouldSwitchChannel(ChannelResult result, ChannelResultApplication application) {
    return application == APPLIED
        && result.failureOptional().isPresent()
        && result.failureOptional().get().switchable()          // ← 风控为 false
        && !result.failureOptional().get().requiresQueryBeforeDecision();
}
```

### 切换通道的硬约束

```java
public Optional<ChannelCapability> fallbackFor(ChannelCode failed, CapabilityRequirement requirement) {
    return eligibleChannels(requirement).stream()
        .filter(c -> c.channel() != failed)
        .filter(c -> c.covers(requirement.requiredCapabilities()))   // ← 能力对等
        .findFirst();
}
```

**宁可失败，也不能切到一个语义不同的通道。**

例如：从支持 manual capture 的 Stripe 切到只支持 SALE 的通道，会导致授权与请款分离的业务语义丢失，资金直接错乱 —— 用户还没发货就被扣款。
