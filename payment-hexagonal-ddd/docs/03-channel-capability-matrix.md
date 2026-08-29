# 03 通道能力矩阵与国内外差异

> 完整配置在 `infrastructure/channel/config/ChannelCapabilityConfiguration.java`。
> 本文档说明为什么这么配，以及这些差异在代码里如何体现。

## 3.1 四处关键差异总览

| 维度 | 国内（微信/支付宝/京东） | 海外（Stripe/PayPal/Antom/Worldpay） |
|---|---|---|
| **交易模型** | SALE，下单即扣款，一步到位 | AUTH_ONLY + CAPTURE，先冻结额度后请款 |
| **退款时效** | 365 天窗口，即时到账 | 180 天窗口，异步（卡退款 5~10 工作日） |
| **幂等维度** | 商户订单号即幂等键，语义强 | 请求头 `Idempotency-Key`，24 小时有效 |
| **角色分层** | 一体化：钱包+收单+清算 | 四层：钱包 / PSP / 收单行 / 卡组织 |
| **争议处理** | 投诉 + 平台介入 | chargeback + representment（申诉） |
| **担保交易** | 成熟（确认收货才结算） | 无等价物，只能用 delayed capture 近似 |
| **3DS 挑战** | 无 | 强监管（SCA/PSD2）下常见 |

## 3.2 差异一：交易模型（最核心）

### 国内：SALE

```
用户付款 ──▶ 立即扣款 ──▶ 商户收到钱
```

微信、支付宝、京东都是这个模型。**没有「授权」这个概念**，商户代码里也就不存在请款这一步。

### 海外：两段式

```
用户付款 ──▶ 冻结额度(AUTHORIZED) ──▶ [商户确认可履约] ──▶ 请款(CAPTURE) ──▶ 钱划走
                    │                                            │
                    └── 7 天不请款自动释放                        └── 请款额 ≤ 授权额
```

### 这个差异会踩哪些坑

| 坑 | 说明 |
|---|---|
| **授权会过期** | 卡组织通常 7 天。酒店预授权最典型：用户住完店，商户请款时授权已失效。 |
| **可部分请款** | 授权 100 美元，只发了 80 美元的货，请款 80 即可，剩余 20 自动解冻。国内想实现同样效果只能「全额支付 + 部分退款」，用户体验和资金占用完全不同。 |
| **请款不得超过授权额** | 部分通道允许 115% 上浮（加油、小费场景），超出必须走增量授权。 |
| **未请款要用 VOID，不是 REFUND** | 钱根本没扣，退不了。对未请款的授权调退款，通道会返回「交易不存在」。 |

### 代码体现

```java
// PayerIdentity / Authorization 值对象
public record Authorization(
    String channelAuthorizationId,
    Money authorizedAmount,
    Instant authorizedAt,
    Instant expiresAt,          // null 时按 DEFAULT_TTL = 7 天
    String networkToken
) {
    public boolean isExpiredAt(Instant now) { ... }
    public boolean covers(Money captureAmount) { ... }   // 请款不得超额
}
```

```java
// PaymentOrder.requestCapture：聚合根自己守住三条校验
if (status != AUTHORIZED)              throw ...;   // 状态必须是已授权
if (authorization.isExpiredAt(now))    throw ...;   // 授权未过期
if (!authorization.covers(amount))     throw ...;   // 请款额不超授权额
```

能力位上，海外通道有 `AUTH_ONLY / CAPTURE / VOID`，国内通道有 `SALE`。**两者互斥。**

## 3.3 差异二：退款

| | 微信 | 支付宝 | 京东 | Stripe | PayPal | Antom | Worldpay |
|---|---|---|---|---|---|---|---|
| 部分退款 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| 多次部分退款 | 最多 50 次 | 不限 | 最多 10 次 | 不限 | 不限 | 不限 | ❌ 只能退一次 |
| 退款窗口 | 365 天 | 365 天 | 365 天 | 无限制 | 180 天 | 180 天 | 180 天 |
| 即时到账 | ✅ | ✅ | ✅ | ❌ 异步 | ❌ 异步 | ❌ 异步 | ❌ 异步 |
| 需证书 | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| 原路退回 | ✅ 强制 | ✅ 强制 | ✅ 强制 | ✅ | ❌ **可退余额** | ✅ | ✅ |
| 结算后可退 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ❌ |

### 三个容易忽略的点

**1. PayPal 的 `originalMethodOnly = false`**

退款可以退到用户的 PayPal 账户余额，而不必原路退回银行卡。国内通道基本都强制原路退回。这个差异直接影响用户体感：退到余额即时可用，退到卡要等好几天。

**2. Worldpay 不支持结算后退款**

资金已结算给商户后无法再退。业务上必须控制结算节奏，或预留保证金账户应对退款。

**3. Worldpay 不支持多次部分退款**

一笔交易只能退一次，无论金额是否为全额。想分多次退，必须在业务层自行拆单 —— **这类限制如果不在能力矩阵里声明，就会在运行时才被发现，那时候用户的钱已经收了。**

### 代码体现

```java
RefundEligibilityService.check(order, amount, capability, settled, now)
```

校验顺序（每条都能追溯到具体的能力位）：

1. 订单是否已支付
2. 币种是否一致
3. 金额是否超过剩余可退
4. 通道是否支持退款（`Capability.FULL_REFUND`）
5. 部分退款是否支持（`Capability.PARTIAL_REFUND`）
6. 退款窗口是否过期（`RefundPolicy.refundWindow`）
7. 部分退款次数是否超限（`RefundPolicy.maxPartialRefundCount`）
8. 结算后是否可退（`RefundPolicy.supportsRefundAfterSettlement`）

**为什么在下单前就校验，而不是打到通道再拿错误码？** 退款失败的成本远高于校验成本：一次失败的退款意味着客服介入、用户投诉、可能的资金挂账。

## 3.4 差异三：幂等维度

这是**最容易被忽略、后果最严重**的一处差异。

| | 国内 | 海外 |
|---|---|---|
| 幂等键落点 | 商户订单号（`out_trade_no`） | 请求头（`Idempotency-Key` / `PayPal-Request-Id`） |
| 有效期 | 无期限 | 24 小时（Worldpay 7 天） |
| 同键不同参数 | 返回原请求结果 | **Stripe 直接拒绝** |

### Stripe 的 REJECT 行为

`Idempotency-Key` 要求**同键同参数**。同键不同参数会被直接拒绝。这意味着：

> **重试必须复用完全相同的请求体，不能「改个金额再试一次」。**

### Worldpay 的 UNDEFINED（最危险）

重试时通道的行为**未定义** —— 既可能返回原结果，也可能再扣一笔。

```java
new IdempotencySpec(
    IdempotencyScope.REQUEST_HEADER,
    Duration.ofDays(7),
    ConflictBehaviour.UNDEFINED,     // ← 不能依赖通道的幂等保护
    "Worldpay 重试行为未定义，必须靠主动查单兜底")
```

**处理方式**：超时后必须走「主动查单」确认，而不是盲目重试。

### 为什么幂等键不能随机生成

```java
// ❌ 错误
String key = UUID.randomUUID().toString();
attempt.setIdempotencyKey(key);
channelPort.pay(request);      // ← 进程在这里崩溃
// 重试：又生成一个新 key → 通道视为全新交易 → 重复扣款
```

生成 key、持久化、调用通道三步不是原子的。用随机 key，一旦丢失就永远找不回来。

```java
// ✅ 正确：确定性推导
IdempotencyKeyFactory.channelPaymentKey(orderId, channel)   // "pay:PAY001:STRIPE"
```

同一个（订单，通道）组合，无论在哪台机器、第几次计算，得到的 key 完全相同。崩溃后重试算出来的还是同一个 key。

> 请款键要带序号（`cap:PAY001:2`）：一笔授权可能分多次部分请款，每次的键必须不同，否则第二次请款会被当成第一次的重复请求而返回原结果。

## 3.5 差异四：角色分层

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

### 国内扁平 vs 海外分层

- **国内**：微信、支付宝既是钱包、又是收单、又是清算。一家吃完全链路，一次集成、一次对账。
- **海外**：Apple Pay 只做网络令牌化，**既不授权、也不请款、更不结算**。真正的下单通道仍是 Stripe / Worldpay / Antom。

### Apple Pay / 银联会被路由排除

```java
public boolean isAcquirable() {
    return category != ChannelCategory.WALLET && category != ChannelCategory.SCHEME;
}
```

- `APPLE_PAY` 的 category 是 `WALLET`
- `UNIONPAY` 的 category 是 `SCHEME`

两者的 `isAcquirable()` 都是 false，**能力矩阵会在路由阶段自动排除**。

配置里保留它们是有意为之 —— 演示「配置有、路由用不了」的情况。

### 搞反了会怎样

如果把 Apple Pay 当通道建模：

- 直接向 Apple 发起下单 → **无此接口**
- 无法处理 3DS → **3DS 是下游 PSP 的职责**
- 无法退款 → **退款要找 PSP**

## 3.6 能力位清单

按分组：

**交易模型**：`SALE` / `AUTH_ONLY` / `CAPTURE` / `PARTIAL_CAPTURE` / `INCREMENTAL_AUTH` / `VOID`

**下单形态**：`FRONTEND_SDK_INVOKE` / `QR_PRECREATE` / `HOSTED_REDIRECT` / `BARCODE_DIRECT` / `SERVER_TO_SERVER`

**安全鉴权**：`THREE_DS_CHALLENGE` / `NETWORK_TOKENIZATION` / `CERT_BASED_SIGN` / `ASYM_KEY_SIGN` / `WEBHOOK_SIGNATURE`

**退款**：`FULL_REFUND` / `PARTIAL_REFUND` / `MULTIPLE_PARTIAL_REFUND` / `INSTANT_REFUND` / `REFUND_QUERY` / `REVERSE`

**订单管理**：`ORDER_QUERY` / `ORDER_CLOSE` / `ORDER_CANCEL`

**异步通知**：`ASYNC_NOTIFY` / `NOTIFY_RETRY` / `NOTIFY_OUT_OF_ORDER`

**币种**：`MULTI_CURRENCY` / `PRESENTMENT_CURRENCY`

**争议处理**：`DISPUTE` / `CHARGEBACK_REPRESENTMENT`

**增值能力**：`ESCROW` / `SETTLEMENT_SPLIT` / `RECURRING`

### 两个容易混淆的能力位

**`REVERSE`（撤销交易）vs `VOID`（撤销授权）**

| | REVERSE | VOID |
|---|---|---|
| 针对 | **当天已支付**的交易 | **已授权未请款**的交易 |
| 效果 | 若已支付则原路退回（免手续费、即时）；若未支付则关闭订单 | 解冻冻结的额度 |
| 存在范围 | 国内微信/支付宝 | 海外卡体系 |
| 账务流水 | 有（一进一出） | 无（交易消失） |

**海外没有 REVERSE**。要达成类似效果必须区分：未请款 → VOID；已请款 → REFUND（且受 180 天窗口限制）。

**`ESCROW`（担保交易）vs delayed capture**

国内担保交易成熟：用户付款后钱在平台，确认收货才结算给商家。

海外卡体系没有等价物，只能靠「先授权、发货后再请款」近似模拟。但两者有本质区别：担保交易的钱**已经划走了**（只是没结算给商家），而授权阶段的钱**根本没划走**（只是冻结了额度）。

## 3.7 通知规范

| 通道 | 方式 | 重试次数 | 覆盖时长 | 乱序 |
|---|---|---|---|---|
| 微信 | PUSH | 15 次 | 24 小时 | 是 |
| 支付宝 | PUSH | 8 次 | 24 小时 | 是 |
| 京东 | PUSH | 6 次 | 12 小时 | 是 |
| 银联 | PUSH | 5 次 | 24 小时 | 否 |
| Stripe | PUSH (webhook) | 3 次 | 3 天 | 是 |
| PayPal | PUSH | 6 次 | 3 天 | 是 |
| Antom | PUSH | 8 次 | 24 小时 | 是 |
| Worldpay | PUSH | 5 次 | 24 小时 | 否 |

**关键结论：所有通道的通知都会丢。**

重试次数有限（微信 15 次、Stripe 只有 3 次），耗尽后不再推送。回调地址抖动几分钟、发版重启、证书过期、防火墙策略变更，都可能让通知全部丢失。

> 因此**主动查单是必需能力，不是可选兜底**。见 `PaymentApplicationService.compensatePendingPayments()`。

## 3.8 状态映射表

各家状态字符串 → 归一化状态：

| 通道 | 待支付 | 用户支付中 | 已授权 | 成功 | 失败 | 关闭 |
|---|---|---|---|---|---|---|
| 微信 | `NOTPAY` | `USERPAYING` | — | `SUCCESS` | `PAYERROR` | `CLOSED` / `REVOKED` |
| 支付宝 | `WAIT_BUYER_PAY` | — | — | `TRADE_SUCCESS` / `TRADE_FINISHED` | — | `TRADE_CLOSED` |
| 京东 | `WAIT` | — | — | `SUCCESS` | `FAILED` | `CLOSED` |
| 银联 | `02` | — | `AUTHORIZED` | `00` | `01` | `03` |
| Stripe | `requires_action` | — | `requires_capture` | `succeeded` | — | `canceled` |
| PayPal | `CREATED` | `PAYER_ACTION_REQUIRED` | `APPROVED` | `COMPLETED` | — | `VOIDED` |
| Antom | `PROCESSING` | — | `AUTHORIZED` | `SUCCESS` | `FAIL` | `CLOSED` |
| Worldpay | `PENDING` | — | `AUTHORIZED` | `CAPTURED` / `SETTLED` | `REFUSED` | `CANCELLED` |

### 为什么归一化状态必须双轨保留原始值

**支付宝 `TRADE_SUCCESS` vs `TRADE_FINISHED`**：两者归一化后都是 `SUCCEEDED`，但语义不同 —— `TRADE_SUCCESS` 可退款，`TRADE_FINISHED` **不可退款**。

只看归一化状态就发起退款，会对一笔注定失败的请求白跑一趟。

类似地：
- 微信 `PAYERROR` 可能是余额不足，也可能是风控拦截，运营处理截然不同。
- Worldpay `SETTLED` 表示资金已过清算窗口，这时候再想退款就晚了。

这就是为什么 `ChannelRawStatus` 必须与归一化状态一并持久化。
