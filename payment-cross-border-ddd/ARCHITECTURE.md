# 支付系统 DDD 演示工程 · 架构设计

> 定位：**用于学习分析的骨架工程**。9 个国内外通道、DDD 分层、通道归一化、幂等与一致性。
> 不追求生产可用，但每一个抽象、每一个决策都指向真实工程问题。

---

## 一、先说三个决定成败的认知

在进入架构之前，有三个认知如果不校准，后面所有抽象都是错的。

### 1.1 Apple Pay 不是通道，是凭证网络

这是整套设计里最容易搞错的一点，也是检验抽象是否正确的试金石。

Apple Pay 只产出一段加密的 `PKPaymentToken`，**它自己完全不碰资金清算**。这段 token 必须交给一个真正的收单行（Stripe / Worldpay / Adyen）去解密并请款。

Worldpay 的官方文档直接印证了这点：Apple Pay 的 payload 被塞进 `<APPLEPAY-SSL>` 元素，通过 Worldpay 的 XML 网关提交。

**如果误以为 Apple Pay 是通道，会产生两个后果：**

| 后果 | 说明 |
|---|---|
| 无法容灾 | Stripe 挂了，Apple Pay 按钮就得下线。而实际上换 Worldpay 就能继续服务 |
| 能力判断错误 | 退款期限、拒付能力、币种支持全部取决于**底层收单行**，不是 Apple。把能力写在 Apple Pay 上是错的 |

**正确建模**：支付方式（`PaymentMethodType`）与通道（`ChannelCode`）是两个**正交维度**：

```
支付方式(PaymentMethodType)  ×  通道(ChannelCode)  →  能力矩阵(ChannelCapability)
```

`ApplePayAdapter` 是一个**委托适配器**：实现统一 SPI，但内部持有底层 PSP，所有资金操作全部转交。它的 `channelCode()` 返回的是 delegate 的编码——因为真正扣款的是 delegate。

### 1.2 支付是一个过程，不是一次调用

早期支付 API（Stripe 的 charge 模式）是"一次调用，要么成功要么失败"。这个模型无法表达"需要 3DS 验证""需要手动请款"这些中间态。

Stripe 的 PaymentIntent 之所以成为行业标杆，是因为它把支付建模成**显式状态机**：

```
requires_payment_method → requires_confirmation → requires_action(3DS)
      → processing → succeeded
                   ↘ requires_capture（手动请款）
                   ↘ canceled / payment_failed
```

**推论**：网络超时绝不能判失败。超时意味着"不知道"，必须返回 `UNKNOWN`，保持订单"支付中"，然后**以主动查证为准**。任何把超时映射成失败的代码，都是一个潜在的掉单 bug。

### 1.3 回调不可信

异步回调有四类问题，必须逐一应对：

| 问题 | 应对 |
|---|---|
| 可能丢失 | 主动查证补偿（指数退避轮询） |
| 可能重复 | `notifyId` 去重 |
| 可能乱序 | 状态机终态守卫 |
| 可能被伪造 | 严格验签 + **金额比对** |

第四条里的"金额比对"是最常被忽略的：攻击者篡改回调报文里的金额，若系统只改状态不校验金额，**1 分钱就能买走 1000 元的商品**。本工程在 `PaymentOrder.applyChannelResult` 中强制校验，不一致直接抛异常。

---

## 二、限界上下文划分

```
┌─────────────────┐   PaymentSucceeded    ┌─────────────────┐
│   收单上下文     │ ────────────────────→ │   结算上下文     │
│   Acquiring     │      (领域事件)        │   Settlement    │
│                 │                        │                 │
│  PaymentOrder   │                        │ SettlementOrder │
│  PaymentAttempt │                        │ SplitInstruction│
│  RefundOrder    │                        │   Withdrawal    │
└─────────────────┘                        └─────────────────┘
        │ 依赖                                      │
        ↓                                           │
┌─────────────────────────────────────────────────────────────┐
│              通道网关上下文  Channel Gateway                  │
│  ChannelCapability / PaymentChannelPort / ChannelRouter      │
│              （9 个通道适配器的抽象层）                        │
└─────────────────────────────────────────────────────────────┘
```

**为什么结算要独立成上下文？**

收单关心"这笔钱能不能收到"，结算关心"收到的钱什么时候、以什么比例给到商户"。业务节奏完全不同：收单是秒级，结算是 T+1 日终批量。若混在一起，日终批处理会拖垮在线交易链路。

上下文之间通过**领域事件**协作，结算逻辑变更不影响支付主链路。

---

## 三、分层架构与依赖方向

```
        ┌──────────────────────────────────────────┐
        │            payment-bootstrap              │  启动器
        └───────────────────┬──────────────────────┘
                            │
    ┌───────────────────────┴────────────────────────┐
    │                                                 │
┌───▼──────────────┐                        ┌─────────▼────────┐
│ payment-         │                        │ payment-         │
│ interfaces       │  HTTP / 回调入口        │ infrastructure   │
│ (接入层)          │                        │ (仓储/幂等/定时)   │
└───┬──────────────┘                        └─────────┬────────┘
    │                                                 │
    │              ┌──────────────────────────────────┘
    │              │
┌───▼──────────────▼─────────────────────────────────────────┐
│                  payment-application                        │
│        用例编排 / 事务边界 / 幂等守卫 / Outbox / Saga          │
└───────────────────────────┬─────────────────────────────────┘
                            │
        ┌───────────────────┴──────────────────┐
        │                                       │
┌───────▼──────────┐              ┌─────────────▼──────────┐
│ payment-domain   │◀─────────────│ payment-channel-        │
│                  │  实现 SPI     │ adapter                │
│ 聚合/实体/值对象  │              │  9 个通道适配器          │
│ 状态机/领域服务   │              │                         │
│ 仓储接口/端口     │              │                         │
└───────┬──────────┘              └─────────────────────────┘
        │
┌───────▼──────────┐
│ payment-shared-  │  共享内核：Money / Currency / DomainEvent
│ kernel           │  ← 被所有模块依赖，但零外部依赖
└──────────────────┘
```

### 依赖方向的核心约束

| 层 | 可否依赖 Spring | 可否依赖其他层 | 说明 |
|---|---|---|---|
| shared-kernel | ❌ | ❌ | 纯 JDK，只有 Money/异常/事件 |
| domain | ❌ | shared-kernel | **领域层零框架依赖** |
| application | ❌ | domain, shared-kernel | 用例编排，技术关注点 |
| channel-adapter | ❌ | domain, shared-kernel | **实现 domain 定义的 SPI** |
| infrastructure | ✅ | 全部 | 仓储、幂等存储、定时任务 |
| interfaces | ✅ | application, domain | HTTP 协议转换 |
| bootstrap | ✅ | 全部 | 启动与装配 |

**最关键的一条：箭头指向 domain，而不是从 domain 指出去。**

`PaymentChannelPort` 定义在 domain 层，实现在 channel-adapter 层。依赖方向是 `adapter → domain`，即**实现依赖抽象**。领域层完全不知道微信、Stripe 的存在。

这样做的收益是可测性：领域模型可以**零 mock 单测**。对一个资金系统，这是巨大的价值——所有核心不变量都能在毫秒级验证。

---

## 四、领域模型

### 4.1 聚合划分

```
PaymentOrder（聚合根）
  ├── PaymentAttempt（局部实体）  —— 一次通道尝试
  └── RefundOrder（局部实体）     —— 退款单
```

**决策：退款为什么在聚合内，而不是独立聚合？**

常见做法是退款单独立成聚合，理由是"退款生命周期独立"。但退款有一条铁律：**累计退款额不得超过原支付额**。这是硬性资金安全约束，必须强一致。

若拆成两个聚合，两笔并发退款各自读到"已退 0"，各自校验通过，同时写入——直接超额退款，产生资损。

DDD 的聚合划分原则第一条就是"**在一致性边界内建模真正的不变量**"。超额退款是真正的不变量，所以退款必须在聚合内。

**决策：为什么需要 PaymentAttempt 这一层实体？**

一次支付可能尝试多个通道：微信失败 → 切支付宝 → 再切银联。每次尝试的 `outTradeNo` **必须不同**（微信/支付宝的 out_trade_no 全局唯一，复用会导致第二次下单直接返回"订单已存在"）。

没有这一层，就无法表达"同一笔订单在第 2 次尝试的第 3 个通道上失败了"，排查时只能看到最终结果，看不到过程。

### 4.2 值对象：Money

Money 内部用 `long minorUnits` 存储最小货币单位，而不是 BigDecimal。

**为什么？**

1. **杜绝浮点污染**：`new BigDecimal(0.1)` 是 `0.1000000000000000055511151231257827`。只要有一次从 double 构造，整条链路就脏了。long 从物理上杜绝这个入口。
2. **通道对齐**：微信、支付宝、Stripe、PayPal 的报文**全部**以最小单位传值。
3. **DB 对齐**：BIGINT 比 DECIMAL 索引更紧凑、跨库迁移无痛。

**它挡掉三个真实事故：**

| 币种 | 坑 | 后果 |
|---|---|---|
| KWD（3 位小数） | 1.234 KWD 存 1234 而非 123 | 多除一次 10 → **10 倍资损** |
| JPY（0 位小数） | 100 日元最小单位就是 100 | 按"乘 100"通用逻辑 → **100 倍长款** |
| 跨币种相加 | 100 JPY + 1 USD | 必须拒绝，而非得 101 |

`allocate()` 方法用于分账的余数分配：100 分按 1:1:1 拆为 `[34, 33, 33]`，**严格保证 sum == 100**。谁自己写 `total * ratio / sum` 谁就会产生 1 分差额，日终对账永远差几分钱。

### 4.3 状态机

```
                    ┌─────────────────────────────────┐
                    ↓                                 │
CREATED ──→ PAYING ──→ PAID ──→ PARTIALLY_REFUNDED ───┤
   │          │         │                             │
   │          │         └────────────────────────────→│
   │          ↓                                       ↓
   │      AUTHORIZED ──→ CAPTURING ──→ PAID      REFUNDED (终态)
   │          │              │
   ↓          ↓              ↓
CLOSED     FAILED        FAILED
(终态)      (终态)        (终态)
```

**两条铁律：**

1. **终态不可变**：`REFUNDED / CLOSED / FAILED` 一旦进入，拒绝任何变更。这是防回调乱序的最后防线。
2. **不存在回退**：`PAID` 不能回到 `PAYING`。用户付完钱，系统不能假装没付。

**为什么状态全集保留两段式（AUTHORIZED / CAPTURING）？**

核心矛盾是国内通道一段式、海外卡组织两段式。若按国内模型设计，接入 Stripe 后没法表达"已授权未请款"；若按两段式统一，国内通道的 AUTHORIZED 就成了永远跳过的空转态。

**取舍**：保留两段式，由 `ChannelCapability.authCaptureSeparated()` 决定一段式通道是否跳过。牺牲一点"统一性"，换取对两类通道的准确表达——这是值得的，因为错误的统一会导致无法支持预授权业务。

---

## 五、通道归一化：9 通道抽象

### 5.1 统一 SPI

```java
public interface PaymentChannelPort {
    ChannelCode channelCode();
    ChannelCapability capability();          // ← 最重要的方法

    PayResponse       pay(PayCommand cmd);
    QueryResponse     query(QueryCommand cmd);
    CloseResponse     close(CloseCommand cmd);
    RefundResponse    refund(RefundCommand cmd);
    CancelResponse    cancel(CancelCommand cmd);   // 撤销 ≠ 退款
    CaptureResponse   capture(CaptureCommand cmd); // 两段式第二步
    NotificationParseResult parseNotification(RawNotification raw);
}
```

**设计决策：能力声明 + 精简操作，而不是"大而全接口 + UnsupportedOperationException"**

常见反模式：接口定义 10 个方法，不支持的实现抛 `UnsupportedOperationException`。这等于**把能力差异从编译期推迟到运行期**，上线才炸。

本设计改为：能力差异通过 `ChannelCapability` 在**编译期声明**，调用前先查能力，路由阶段就能过滤掉不支持的通道。

### 5.2 能力矩阵：9 通道对比

| 通道 | 收单模式 | 两段式 | 撤销 | 多次部分退款 | 退款期限 | 拒付 | 幂等机制 | 签名算法 | 报文 |
|---|---|---|---|---|---|---|---|---|---|
| **微信支付** | 钱包 | ❌ | ❌ | ✅ | 365天 | ❌ | 仅订单号 | WECHATPAY_RSA | JSON(加密) |
| **支付宝** | 钱包 | ❌ | **✅** | ✅ | 365天 | ❌ | 仅订单号 | ALIPAY_RSA2 | form表单 |
| **京东支付** | 网关 | ❌ | ❌ | **❌** | 365天 | ❌ | 仅订单号 | HMAC | form表单 |
| **银联** | 网关 | **✅** | ✅ | ✅ | 180天 | **✅** | 仅订单号 | HMAC | form表单 |
| **PayPal** | 钱包 | ✅ | ✅ | ✅ | 180天 | ✅ | **请求头 Request-Id** | HMAC(需远程验签) | JSON |
| **Stripe** | 卡收单 | ✅ | ✅ | ✅ | 180天 | ✅ | **请求头 Idempotency-Key** | HMAC+时间戳 | JSON |
| **Worldpay** | 卡收单 | ✅ | ✅ | ✅ | 无限制 | ✅ | 仅订单号 | **MAC** | **XML** |
| **Antom** | **聚合** | ✅ | ✅ | ✅ | 180天* | ✅ | **业务字段 paymentRequestId** | HMAC | JSON |
| **Apple Pay** | **凭证网络** | 继承PSP | 继承PSP | 继承PSP | 继承PSP | 继承PSP | 委托PSP | **委托PSP** | 委托PSP |

\* Antom 的 APM 退款期限差异极大：Tamara 120 天、Paidy 365 天、Pagaleve 90 天。能力矩阵取 180 天为保守兜底。

**几个决定性的差异：**

| 差异点 | 说明 | 工程影响 |
|---|---|---|
| `authCaptureSeparated` | 两段式 vs 一段式 | 决定状态机是否要有 CAPTURING/CAPTURED 态 |
| `supportsCancel` | 撤销 vs 只能退款 | 撤销不产生退款单、通常不收手续费 |
| `supportsMultiplePartialRefund` | 京东只支持退一次 | 路由时必须避开，否则第二次退款直接失败 |
| `supportsChargeback` | 拒付是卡组织独有 | 国内是"投诉/争议"，流程完全不同 |
| `idempotencyMode` | 三种幂等形态 | 重试策略必须分开写 |
| `certificateAutoRotation` | 微信/支付宝证书会轮换 | 硬编码证书会导致某天全量验签失败 |

### 5.3 通道各自的坑（写在适配器注释里）

**微信支付**
- 平台证书**自动轮换且不通知**。硬编码证书会在某天突然全量验签失败。
- 回调 `resource` 字段是 AES-256-GCM 密文，必须先解密。顺序：验签 → 解密 → 校验金额。
- **无幂等头**，重试前必须先查单。

**支付宝**
- 支持 `alipay.trade.cancel`：未支付则关闭，已支付则退款，一个接口覆盖两种场景。
- 回调是 form-urlencoded，验签需**剔除 sign/sign_type 后按 key 排序**。
- 必须返回纯字符串 `success`，返回错会导致重投 8 次以上。

**Worldpay**
- **XML 协议**，金额带 `exponent` 属性：`<amount value="1000" currencyCode="GBP" exponent="2"/>`。
- 卡种用 XML 元素名表达：`<VISA-SSL>` / `<APPLEPAY-SSL>`。

**Stripe**
- `Idempotency-Key` 请求头 24 小时有效 → 重试无需先查单。
- Webhook 签名含时间戳，超 5 分钟容忍窗口直接拒绝（防重放）。

**PayPal**
- **Webhook 无法本地验签**，必须回调 PayPal 的 verify 接口。这意味着验签本身是一次网络调用。
- 金额用十进制字符串 "10.00"，而非最小单位整数。

**Antom**
- 聚合收单，一个通道背后 300+ 支付方式 → 天然支持"嵌套通道"。
- `paymentRequestId` 业务字段幂等。
- APM 长尾约束无法穷举（如 PayPay 退款次数 ≤ 20），落到 `extraParams` 逃生舱。

---

## 六、幂等：四层防线

支付系统需要**四层**幂等，缺一不可。它们分别防的是不同的东西：

| 层级 | 防什么 | 实现 |
|---|---|---|
| **① 接入层** | 用户手抖重复点击 | `Idempotency-Key` + **请求指纹** + 原子抢占 |
| **② 业务层** | 客户端 bug 重复提单 | `(merchant_id, merchant_order_no)` 唯一索引 |
| **③ 通道层** | 网络重试重复扣款 | `outTradeNo` 每尝试唯一 + 通道幂等键 |
| **④ 回调层** | 通道重投通知 | `notifyId` 去重 + 状态机终态守卫 |

### ① 接入层：为什么需要请求指纹？

只记录幂等键是不够的。同一个幂等键，若携带不同业务参数（金额从 100 变成 200），说明客户端有 bug。此时必须**拒绝并报 409**，而不是静默返回第一次结果——否则用户以为付了 200，实际只扣了 100。

指纹只包含"决定业务结果"的参数：金额、币种、商户号、商户订单号、支付方式。**不能包含请求时间、traceId、IP**——这些每次都变，会导致指纹永远不匹配，幂等失效。

### ③ 通道层：最容易出错的一层

重试时若复用同一个 `outTradeNo`，微信/支付宝会返回"订单已存在"，重试永远失败；若每次都换新号，又必须确保旧号已失效，否则可能重复扣款。

本工程采用"**每尝试一号**"策略（attemptSeq 编进单号），并在 UNKNOWN 时靠查证兜底。

三种通道幂等形态必须分别处理：

```java
HEADER_IDEMPOTENCY_KEY  → 放进 Idempotency-Key 头（Stripe）
HEADER_REQUEST_ID       → 放进 PayPal-Request-Id 头（PayPal）
BUSINESS_FIELD          → 放进 paymentRequestId 业务字段（Antom）
MERCHANT_ORDER_NO_ONLY  → 无幂等机制，重试前必须先查单（微信/支付宝）
```

---

## 七、一致性设计

### 7.1 Outbox 模式

**问题**：写库和发消息是两个独立系统，无法放在同一事务里。

- 先写库后发消息 → 消息发送失败，下游永远不知道订单已支付
- 先发消息后写库 → 库写入失败回滚，下游却收到"支付成功" → **发货但没收到钱**

**解法**：把要发的消息作为一行数据，**和业务数据在同一个本地事务里**写入 outbox 表。事务提交后，由独立线程发往 MQ。

**代价**：消息可能重复投递（投递成功但标记失败，下次重投），因此**消费端必须幂等**。这是 Outbox 模式的必要配套。

### 7.2 查证补偿：指数退避

```
下单后 10s → 30s → 1min → 5min → 30min → 2h（停止并关单）
```

**为什么是指数退避？** 绝大多数订单在 1 分钟内完成支付，密集轮询前 1 分钟能最快确认状态；而迟迟未支付的订单大概率是用户放弃了，没必要高频查询（还浪费通道查询配额）。

**停止条件**：超过通道订单有效期（微信/支付宝通常 2 小时）后主动关单——此时关单是安全的，因为已超过用户可能完成支付的时间窗。

### 7.3 退款的三重防护

并发退款是最经典的资金安全事故：

```
线程A：读订单 → 已退 0 → 校验通过 → 退款 100
线程B：读订单 → 已退 0 → 校验通过 → 退款 100
结果：原单 100 元，实际退了 200 元
```

三重防护是**纵深防御**，只做一层都不够：

1. **分布式锁**：按订单维度串行化
2. **聚合内校验**：PaymentOrder 内部同一把锁内完成"读-校验-写"
3. **DB 约束**：退款金额 CHECK 约束（最终兜底）

锁可能失效（Redis 抖动），聚合校验可能在极端并发下被绕过，DB 约束则太晚（用户已收到退款成功响应）。

---

## 八、关键设计决策与 Trade-off

这一节是整套设计里最值钱的部分。每个决策都说明：**选了什么、放弃了什么、为什么、代价是什么**。

### D1. 支付方式与通道正交建模

- **选**：`PaymentMethodType` × `ChannelCode` → `ChannelCapability`
- **弃**：合并成一个枚举
- **理由**：Apple Pay 必须能切换收单行做容灾；Antom 一个通道承载 300+ 支付方式
- **代价**：多一层映射，路由逻辑稍复杂

### D2. 能力矩阵声明差异，而非 if-else 判断

- **选**：`ChannelCapability` 数据化描述通道能力
- **弃**：`if (channel == WECHAT) {...}`
- **理由**：差异是**数据**不是**逻辑**。新增通道只需加一个 Bean，不改业务代码
- **代价**：能力项会膨胀，需要定期梳理

### D3. 退款放在聚合内

- **选**：RefundOrder 作为 PaymentOrder 的局部实体
- **弃**：退款独立聚合
- **理由**：累计退款不超额是**真正的不变量**，必须强一致
- **代价**：聚合变大，加载时要带出所有退款单（可用延迟加载缓解）

### D4. Money 用 long 存最小单位

- **选**：`long minorUnits` + `Currency.exponent`
- **弃**：`BigDecimal`
- **理由**：杜绝浮点污染、与通道报文零成本对齐、DB 索引更紧凑
- **代价**：展示层每次要转换；跨币种运算必须显式处理

### D5. 状态全集保留两段式

- **选**：保留 AUTHORIZED / CAPTURING
- **弃**：只做一段式（国内模型）
- **理由**：否则无法接入 Stripe 的预授权业务
- **代价**：国内通道永远跳过这两个态，是"空转"的状态项

### D6. 超时返回 UNKNOWN，不判失败

- **选**：`ChannelResultStatus.UNKNOWN`
- **弃**：超时即失败
- **理由**：超时可能已扣款成功，判失败 = 掉单 = 用户付钱商户没单
- **代价**：必须配套查证补偿，系统复杂度上升

### D7. 回调只当触发器，状态以查证为准

- **选**：收到回调 → 验签 → 去重 → **查证** → 更新
- **弃**：直接用回调内容更新状态
- **理由**：回调不可信（可伪造、可乱序）
- **代价**：多一次通道调用；通道查询有频率限制

### D8. 聚合不依赖外部服务

- **选**：PaymentOrder 纯内存状态变更，调通道由应用层编排
- **弃**：聚合内注入 PaymentChannelPort 自行调用
- **理由**：领域模型可**零 mock 单测**。对资金系统是巨大价值
- **代价**：应用层变厚，编排逻辑集中

### D9. 仓储接口放在 domain 层

- **选**：`PaymentOrderRepository` 接口在 domain，实现在 infrastructure
- **弃**：接口也放 infrastructure
- **理由**："按商户订单号查找"是领域概念，不是技术概念
- **代价**：无显著代价

### D10. 保留 extraParams 逃生舱

- **选**：统一命令对象保留 `Map<String, String> extraParams`
- **弃**：所有参数强类型化
- **理由**：若把微信的 openid、Stripe 的 payment_method、Antom 的 APM 参数都提升为统一字段，接口会迅速腐化成"所有通道参数的并集"，每个通道只用 3 个，其余 20 个都是噪音
- **代价**：类型安全下降，需要文档约束
- **约束**：只允许放通道特有的非核心参数，核心业务字段（金额、订单号、币种）必须在强类型字段

### D11. 不支持的操作返回结构化失败，而非抛异常

- **选**：`CancelResponse.fail(...)` 带错误码
- **弃**：`UnsupportedOperationException`
- **理由**：把能力差异从运行期异常变成可处理的返回值，上层可优雅降级
- **代价**：调用方可能忘记检查返回值

---

## 九、已知简化与演进方向

| 简化项 | 生产环境应替换为 |
|---|---|
| 内存仓储 | MySQL + MyBatis，乐观锁 `UPDATE ... WHERE version=?` |
| 本地锁 | Redisson 分布式锁 |
| 内存幂等存储 | Redis `SETNX ... EX 86400` |
| 内存 Outbox | MySQL outbox 表 + 独立投递线程 |
| notifyId 内存去重 | Redis Set（带 TTL），进程重启不丢 |
| Mock 通道 | 真实 HTTP 客户端 + 证书管理 + 连接池隔离 |
| 硬编码费率 | 配置中心 + 热更新 |
| 无风控 | 规则引擎、限额、黑名单 |
| 无对账 | 渠道账单解析 + 日终对账 + 差错处理 |

**演进优先级建议**：

1. **先做查证补偿与对账**——这两项是资金安全底线，缺了会真金白银地亏钱
2. **再做通道熔断与智能路由**——直接提升成功率，每 0.1% 都是钱
3. **最后做账务与结算**——业务规模起来后才有必要

---

## 十、快速开始

```bash
# 编译 + 跑测试
export JAVA_HOME=/Library/Java/JavaVirtualMachines/openjdk-24.0.1/Contents/Home
mvn clean test

# 启动
mvn -pl payment-bootstrap spring-boot:run
```

### 重点阅读顺序

1. `shared/money/Money.java` —— 金额是所有资金系统的地基
2. `domain/channel/model/ChannelCapability.java` —— 通道归一化的核心
3. `domain/acquiring/model/aggregate/PaymentOrder.java` —— 聚合与不变量
4. `domain/acquiring/statemachine/PaymentStateMachine.java` —— 防资损闸门
5. `adapter/applepay/ApplePayAdapter.java` —— 委托适配器，最反直觉的一处设计
6. `application/command/PaymentCommandService.java` —— 主流程编排
