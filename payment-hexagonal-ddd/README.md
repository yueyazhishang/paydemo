# payment-hexagonal-ddd

支付中台的 **DDD + 六边形架构**教学工程。覆盖国内（微信 / 支付宝 / 京东 / 银联）与海外（Stripe / PayPal / Apple Pay / Antom / Worldpay）共 9 家通道。

> 目标不是跑通生产，而是把「**领域能力抽象**」和「**分层边界**」这两件事讲透。
> 通道调用是模拟的，但架构约束、领域不变量、状态机、幂等都是真的。

---

## 一、这个工程想回答什么问题

接通道很容易，接九家通道很难。难的不是某个接口的字段名，而是：

| 问题 | 朴素做法 | 本工程的做法 |
|---|---|---|
| 各家能力不同，怎么表达？ | `if (channel == WECHAT)` 散落各处 | **能力矩阵**：差异建模成数据，业务只问 `supports(XXX)` |
| 新增通道要改多少地方？ | 业务代码改 N 处 | **加一份能力配置 + 一个适配器**，业务层零改动 |
| 重试会不会重复扣款？ | 每次重试新生成幂等键 | **确定性幂等键**，崩溃后重试仍能算出同一个键 |
| 回调丢了怎么办？ | 等用户投诉 | **通知为主 + 主动查单兜底** |
| 重复/乱序回调怎么处理？ | 按到达顺序覆盖 | **状态机守卫 + 事件时间戳** |
| 海外先授权后请款怎么建模？ | 当成「支付成功」 | **Authorization 值对象 + CaptureMode** |

---

## 二、模块结构（六边形分层）

```
payment-hexagonal-ddd
├── shared-kernel/      共享内核：Money / Currency / TypedId / DomainEvent / 异常体系
├── domain/             领域层：聚合 / 值对象 / 领域服务 / 端口接口
├── application/        应用层：用例编排 / 事务边界 / 幂等 / 事件发布
├── infrastructure/     出站适配器：9 个通道适配器 / 持久化 / 幂等存储 / 回调验签
└── interfaces/         入站适配器：商户 REST API / 通道回调网关
```

### 依赖方向（编译期强制）

```
interfaces ──▶ application ──▶ domain ──▶ shared-kernel
infrastructure ──▶ application ──▶ domain ──▶ shared-kernel

interfaces ──runtime──▶ infrastructure   ← 唯一一处妥协，见下文
```

三条硬性规则：

1. **`domain` 不依赖任何框架**。它的 pom 里只有 `shared-kernel`，没有 Spring、没有 JDBC、没有 HTTP 客户端。领域层能脱离一切基础设施独立跑单测。
2. **`application` 不依赖具体中间件**。它只认 `DomainEventPublisher`、`IdempotencyStore` 这类端口，不知道底下是 Redis 还是内存 Map。
3. **`interfaces` 编译期看不到适配器**。infrastructure 在接口层的 pom 里是 `runtime` 作用域 —— 入站适配器想直接引用 `WeChatPayAdapter`，编译都过不了。

> **关于 `runtime` 作用域**：这是本工程对「纯六边形」的唯一妥协。标准做法是把装配根单独拆成一个 `boot` 模块；这里为了让目录结构少一层，把启动类放在了 `interfaces`，用 Maven 作用域在构建层面兜住了依赖方向。**约束是真实的，不是靠自觉。**

---

## 三、限界上下文

| 上下文 | 定位 | 核心聚合 |
|---|---|---|
| **payment** | 核心域 | `PaymentOrder`（内含 `PaymentAttempt` 实体） |
| **refund** | 核心域 | `RefundOrder`（独立聚合） |
| **channel** | 支撑子域 | 通道能力矩阵、能力匹配、路由 |
| **merchant** | 支撑子域 | `Merchant`（内含 `MerchantApp`、`ChannelContract`） |
| **notify** | 支撑子域 | 回调验签解析、商户通知任务 |

### 两个最重要的聚合设计判断

**`PaymentAttempt` 为什么在支付单聚合内？**

因为存在跨尝试的强不变量：**同一时刻，同一通道只能有一个进行中的尝试**。违反它会导致同一通道并发发起两次下单，而幂等键只保护同键请求 —— 两次独立下单会各自生成交易，这就是重复扣款。要守住它，就必须在一个事务边界内修改。

**`RefundOrder` 为什么独立成聚合？**

因为不存在需要跨退款单例事务保证的不变量。退款的约束是「累计退款不超过实付」，靠支付单上的一个数值字段 + 乐观锁就够了。若强行内嵌，支付单会随退款次数线性膨胀，每次退款都要加载整个聚合，并发的部分退款全部串行化 —— **收益为零，代价很大**。

> 一句话总结：**按不变量划边界，不按「看起来像父子关系」划边界。**

跨聚合一致性用**预留 - 确认**两段式：`reserveRefund()` 占用 → 提交通道 → `applyRefundSucceeded()` 落定 / `applyRefundFailed()` 释放。两个聚合在同一数据库同一事务，因此**不需要分布式事务，也没有最终一致的窗口期**。

---

## 四、能力矩阵：把差异从代码分支变成数据

核心抽象在 `domain/channel/model/ChannelCapability.java`：

```java
public record ChannelCapability(
    ChannelCode channel,
    Set<PaymentMethod> supportedMethods,
    Set<InteractionMode> supportedInteractionModes,
    Set<Capability> capabilities,      // 能力位：AUTH_ONLY / CAPTURE / VOID / REVERSE / ESCROW ...
    AmountConstraint amountConstraint, // 按币种分别配置的限额
    RefundPolicy refundPolicy,         // 退款窗口 / 次数 / 是否即时 / 是否需证书
    NotifySpec notifySpec,             // 推送还是轮询 / 重试次数 / 是否乱序
    IdempotencySpec idempotencySpec,   // 幂等键落点 / 有效期 / 冲突行为
    AuthModel authModel,               // 证书 / RSA / API Key / OAuth2
    SettlementLatency settlementLatency,
    int basePriority
)
```

九家通道的完整配置在 `infrastructure/channel/config/ChannelCapabilityConfiguration.java`，**这是整个工程最值得反复读的文件**。

### 国内外四处关键差异

| 维度 | 国内（微信/支付宝/京东） | 海外（Stripe/PayPal/Worldpay） |
|---|---|---|
| **交易模型** | SALE，下单即扣款 | AUTH_ONLY + CAPTURE，先冻结后请款 |
| **退款窗口** | 365 天，即时到账 | 180 天，异步（5~10 工作日） |
| **幂等维度** | 商户订单号即幂等键 | 请求头 `Idempotency-Key`，24 小时有效 |
| **角色分层** | 一体化（钱包+收单+清算） | 钱包 / PSP / 收单行 / 卡组织 四层 |

### 能力位只声明「能做什么」，不声明「怎么做」

业务代码全程只问：

```java
if (capability.supports(Capability.PARTIAL_REFUND)) { ... }
```

从不写 `if (channel == WECHAT)`。

### 端口隔离：一个能力一个端口

常见错误是定义一个「万能网关接口」，让所有通道实现，不支持的方法抛 `UnsupportedOperationException`。这有两个恶果：调用方**编译期**无法知道某通道是否支持某能力；`UnsupportedOperationException` 满天飞后，再也分不清「真的不支持」和「还没实现」。

本工程的做法是**一个能力一个端口**，适配器只实现自己真正具备的：

| 端口 | 微信 | 支付宝 | 京东 | 银联 | Stripe | PayPal | Antom | Worldpay | Apple Pay |
|---|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| `ChannelPaymentPort` 下单 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ❌ |
| `ChannelQueryPort` 查单 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ❌ |
| `ChannelCapturePort` 请款 | ❌ | ❌ | ❌ | ✅ | ✅ | ✅ | ✅ | ✅ | ❌ |
| `ChannelVoidPort` 撤销授权 | ❌ | ❌ | ❌ | ✅ | ✅ | ✅ | ✅ | ✅ | ❌ |
| `ChannelReversePort` 撤销交易 | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| `ChannelClosePort` 关单 | ✅ | ✅ | ✅ | ❌ | ❌ | ❌ | ✅ | ❌ | ❌ |
| `ChannelRefundPort` 退款 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ❌ |

**Apple Pay 一行全空是刻意的** —— 它不是通道，是钱包。详见下文。

---

## 五、角色分层：Apple Pay 不是通道

这是国内开发者最容易搞错的一点。

```
用户 ──▶ 钱包层(WALLET)      Apple Pay / Google Pay / 微信 / 支付宝
          │  产出 payment token（网络令牌）
          ▼
  PSP 层   Stripe / Antom / Braintree
          │  统一 API + 内部再路由多家收单行 + 风控 + 3DS
          ▼
 收单层(ACQUIRER)  Worldpay / Adyen / 银行
          │  把交易送进卡组织网络
          ▼
 卡组织(SCHEME)    Visa / Mastercard / 银联 / JCB
                   定义 auth / capture / void / refund / chargeback 语义
```

- **国内是「扁平」的**：微信、支付宝既是钱包、又是收单、又是清算，一家吃完全链路。
- **海外是「分层」的**：Apple Pay 只做网络令牌化，**既不授权、也不请款、更不结算**。真正的下单通道仍是 Stripe / Worldpay / Antom，Apple Pay 只是它们支持的一种 `PaymentMethod`。
- **银联同理**：它是卡组织（`SCHEME`），`isAcquirable()` 为 false，会被路由自动排除。配置里保留它，正是为了演示「配置有、路由用不了」。

判断对了有什么用？如果建模搞反（把 Apple Pay 当通道），会出现：直接向 Apple 发起下单 → 无此接口；无法处理 3DS（3DS 是下游 PSP 的职责）；无法退款（退款要找 PSP）。

---

## 六、状态机：防御乱序与终态覆盖

全部合法转移集中在 `domain/payment/service/PaymentStateMachine.java`，一张表看完全部规则。

```
CREATED ──▶ ROUTING ──▶ PAYING ──┬──▶ AUTHORIZED ──▶ CAPTURING ──▶ SUCCEEDED
    │           │         │      │         │              │             │
    │           │         │      └──▶ USERPAYING          │             ▼
    │           │         │                               │        REFUNDING ──▶ PARTIAL_REFUNDED ──▶ REFUNDED
    │           │         └──▶ FAILED（终态）              └──▶ FAILED
    └──▶ CLOSED（终态）                                   └──▶ CLOSED
```

三条关键约束：

1. **终态不可逆**。`FAILED / CLOSED / REFUNDED` 一旦进入，不允许任何转移。
2. **已支付不能关闭**。`SUCCEEDED → CLOSED` 不在合法转移表中 —— 要终止必须走退款，否则形成账务黑洞。
3. **`from == to` 视为合法**（幂等）。通道重复投递同一条通知时，重复应用相同状态不应报错。

### 归一化状态必须双轨保留原始值

支付宝的 `TRADE_SUCCESS`（可退款）与 `TRADE_FINISHED`（不可退款）归一化后都是 `SUCCEEDED`。**只看归一化状态就发起退款，会对一笔注定失败的请求白跑一趟。** 因此 `ChannelRawStatus` 必须与归一化状态一并持久化。

---

## 七、幂等：三层，一层都不能少

| 层次 | 机制 | 防的是什么 |
|---|---|---|
| 接口层 | `Idempotency-Key` 头 | 商户重试导致重复下单 |
| 业务层 | `(app_id, merchant_order_no)` 唯一索引 | 同一笔业务重复生成支付单 |
| 通道层 | `IdempotencyKeyFactory` 确定性生成 | 我方重试导致通道重复扣款 |

### 为什么幂等键不能用 UUID

```java
String key = UUID.randomUUID().toString();
attempt.setIdempotencyKey(key);
channelPort.pay(request);      // ← 进程在这里崩溃
// 重试时：又生成一个新 key → 通道视为全新交易 → 重复扣款
```

生成 key、持久化、调用通道三步不是原子的，进程随时可能在中间挂掉。**用随机 key，一旦丢失就永远找不回来。**

正确做法是**用业务标识确定性推导**：

```java
IdempotencyKeyFactory.channelPaymentKey(orderId, channel)   // "pay:PAY001:STRIPE"
```

同一个（订单，通道）组合，无论在哪台机器、第几次计算，得到的 key 完全相同。崩溃后重试算出来的还是同一个 key，通道正确识别为重复请求并返回原结果。

> 请款键要带序号（`cap:PAY001:2`）：一笔授权可能分多次部分请款，每次的键必须不同，否则第二次请款会被当成第一次的重复请求。

---

## 八、回调：最危险也最容易做错的入口

处理顺序**不能颠倒**：

1. **先验签**。不通过直接拒绝，绝不解析报文、绝不查库、绝不改状态。
2. 验签通过后才解析成归一化内容。
3. 定位订单 → 状态机守卫（乱序丢弃）→ 聚合根自己推进状态。
4. 落库、发事件、通知商户。

### 必须用原始报文体验签

不能用 `@RequestBody SomeDto` 让 Spring 反序列化后再验签 —— 反序列化会丢失原始字节顺序与空格，签名必然校验失败。**这是接微信/支付宝回调时最常见的坑。**

### 业务失败也要返回 2xx

返回 5xx 会让通道按重试策略反复推送（微信 15 次），同一笔问题被放大十几次，告警淹没一切，而问题本身一点没解决。只有**验签失败**才返回 401 —— 那通常意味着有人在伪造回调，属于安全事件。

### 「订单已关闭但用户已付款」

这是最危险的资金场景：钱进了我们的账，订单却是关闭状态，既不发货也不退款。`ChannelResultApplication.TERMINAL_CONFLICT_PAID_AFTER_CLOSE` 专门标记这种情况，**必须触发自动原路退款**，而不是记一条异常就算完。

---

## 九、快速开始

```bash
# 编译（JDK 17+）
mvn clean compile

# 启动
mvn -pl interfaces spring-boot:run

# 下单（国内商户，走微信/支付宝/京东）
curl -X POST http://localhost:8080/api/v1/payments \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-001' \
  -d '{
    "appId": "APP00000000000001",
    "merchantOrderNo": "ORDER_20260829_001",
    "paymentMethod": "WECHAT_NATIVE",
    "amount": 88.88,
    "currency": "CNY",
    "terminal": "WEB",
    "subject": "测试商品"
  }'

# 下单（跨境商户，走 Stripe/PayPal/Antom/Worldpay）
curl -X POST http://localhost:8080/api/v1/payments \
  -H 'Content-Type: application/json' \
  -d '{
    "appId": "APP00000000000002",
    "merchantOrderNo": "GLOBAL_001",
    "paymentMethod": "CARD",
    "amount": 19.99,
    "currency": "USD",
    "terminal": "APP"
  }'

# 主动查单
curl -X POST http://localhost:8080/api/v1/payments/{paymentOrderId}/sync

# 退款
curl -X POST http://localhost:8080/api/v1/refunds \
  -H 'Content-Type: application/json' \
  -d '{
    "appId": "APP00000000000001",
    "paymentOrderId": "{paymentOrderId}",
    "merchantRefundNo": "RF_001",
    "amount": 10.00,
    "currency": "CNY",
    "reason": "用户申请"
  }'
```

> 两个演示商户的签约配置不同：国内商户只签了国内三家，对它发起 `CARD` 支付会**在路由阶段就被拒绝**（无签约通道），而不是等打到通道才报「商户号不存在」。

---

## 十、文档索引

| 文档 | 内容 |
|---|---|
| [01-架构总览与六边形分层](docs/01-architecture-overview.md) | 分层、依赖规则、端口与适配器 |
| [02-限界上下文与聚合设计](docs/02-bounded-context-and-aggregate.md) | 上下文切分、聚合边界论证、跨聚合一致性 |
| [03-通道能力矩阵与国内外差异](docs/03-channel-capability-matrix.md) | 九个通道的完整差异对比 |
| [04-状态机与生命周期](docs/04-state-machine.md) | 状态转移表、乱序处理、终态冲突 |
| [05-幂等与并发控制](docs/05-idempotency-and-concurrency.md) | 三层幂等、乐观锁、分布式锁的取舍 |
| [06-关键设计决策ADR](docs/06-adr.md) | 每个取舍的理由与被否决的方案 |

---

## 十一、已知简化（教学取舍）

| 简化 | 生产应该怎么做 |
|---|---|
| 通道调用是确定性模拟 | 真实 HTTPS + 签名 + 超时 + 解密敏感字段 |
| 持久化用内存 Map | MySQL 分库分表 + 乐观锁 + 联合索引 |
| 事件发布是同步打印 | **事务性发件箱（Transactional Outbox）** |
| 幂等存储用内存 Map | Redis + `SETNX` 原子占用 |
| 分布式锁用内存 Map | Redis Redlock 或数据库锁 |
| 没有实现结算上下文 | 结算批次、手续费、对账、差错处理 |
| 没有对账与差错 | 通道账单解析、长短款、差错工单 |
