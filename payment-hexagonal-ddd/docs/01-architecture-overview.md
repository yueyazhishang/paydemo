# 01 架构总览与六边形分层

## 1.1 为什么是六边形，而不是三层

传统三层（Controller / Service / DAO）在支付系统里会迅速腐化，典型症状：

```
PaymentServiceImpl（3000 行）
├── 参数校验
├── 查商户配置
├── if (channel == WECHAT) { 拼微信报文、加证书签名、发 HTTPS }
├── else if (channel == ALIPAY) { 拼支付宝报文、RSA2 签名、发 HTTPS }
├── else if (channel == STRIPE) { ... }
├── 更新订单状态
├── 发 MQ
├── 通知商户
└── 记录日志
```

问题不在于代码长，而在于**三个关注点被焊死在一起**：

1. 业务规则（状态能不能转、金额超没超）
2. 外部协议（微信的 XML、Stripe 的 JSON、各自的签名算法）
3. 编排（先做什么后做什么、事务边界在哪）

后果是：换一家通道要改业务代码；给业务规则写单测必须启动 Spring 和 Mock 掉 HTTP；想加一个定时任务复用逻辑，发现逻辑全在 Service 里跟 HTTP 耦死了。

六边形的解法是**把外部依赖全部推到边界上**：

```
                    ┌─────────────────────────────────────┐
   商户 REST  ──────▶│          interfaces                 │  入站适配器
   通道回调   ──────▶│  Controller / 协议转换 / 鉴权        │
                    └──────────────┬──────────────────────┘
                                   │ 调用
                    ┌──────────────▼──────────────────────┐
                    │          application                 │  用例编排
                    │  事务边界 / 幂等 / 事件发布            │
                    └──────────────┬──────────────────────┘
                                   │ 调用领域行为
    ┌──────────────────────────────▼──────────────────────────────┐
    │                          domain                              │
    │   聚合根 / 值对象 / 领域服务                                   │
    │                                                              │
    │   出站端口（接口）：                                           │
    │     ChannelPaymentPort / ChannelQueryPort / ChannelRefundPort │
    │     PaymentOrderRepository / MerchantRepository              │
    │     ChannelCapabilityQuery / ChannelHealthQuery              │
    │     DomainEventPublisher / IdempotencyStore                  │
    └──────────────────────────────▲──────────────────────────────┘
                                   │ 实现
                    ┌──────────────┴──────────────────────┐
                    │        infrastructure                │  出站适配器
                    │  9 个通道适配器 / 持久化 / 幂等存储    │
                    └─────────────────────────────────────┘
```

**箭头方向是关键**：外层的箭头指向内层，内层的箭头绝不指向外层。领域层定义了「我需要什么」（端口），但完全不知道谁来实现、怎么实现。

## 1.2 模块与依赖

```
interfaces ──▶ application ──▶ domain ──▶ shared-kernel
infrastructure ──▶ application ──▶ domain ──▶ shared-kernel
interfaces ──runtime──▶ infrastructure
```

用 `mvn dependency:tree` 可以验证。三条硬性规则：

### 规则一：domain 不依赖任何框架

domain 的 pom 里只有 `shared-kernel`。没有 Spring、没有 JDBC、没有 HTTP 客户端、没有任何通道 SDK。

**验证方法**：在 domain 目录下 grep `org.springframework`，结果应为空。

这条规则的价值不是洁癖，而是**可测试性**。领域层的单测不需要 Spring 容器、不需要 Mock 数据库、不需要起服务 —— 直接 `new` 一个对象就能测，测试跑完只要几十毫秒。支付领域有大量边界规则（授权过期、退款窗口、金额精度、状态转移），这些规则必须能被高频、确定性地测试。

### 规则二：application 不依赖具体中间件

它只认端口：`DomainEventPublisher`、`IdempotencyStore`、`DistributedLock`。底下是 Redis 还是内存 Map，应用层不知道也不关心。

### 规则三：interfaces 编译期看不到适配器

infrastructure 在接口层 pom 里是 `runtime` 作用域。入站适配器想直接 `new WeChatPayAdapter()`，**编译都过不了**。

这是用**构建工具**表达架构约束，而不是靠 Code Review 提醒。后者会失效（人总会犯懒），前者不会。

> **关于 runtime 作用域的妥协**：标准六边形会把装配根单独拆成一个 `boot` 模块。这里为了少一层目录，把启动类放在 interfaces，但用 Maven 作用域在构建层面兜住了依赖方向。约束是真实的。

## 1.3 端口设计：一个能力一个端口

### 反面教材

```java
interface ChannelGateway {
    Result pay(req);
    Result query(req);
    Result refund(req);
    Result capture(req);
    Result voidAuth(req);
    Result close(req);
}
```

每接一家不支持全部能力的通道，都要把不支持的方法实现成：

```java
@Override
public Result capture(req) {
    throw new UnsupportedOperationException();
}
```

两个恶果：

1. **调用方编译期无法知道**某通道是否支持某能力，只能运行时炸。
2. `UnsupportedOperationException` 满天飞后，**再也分不清「这个能力真的不支持」和「这里还没实现」**。

### 本工程的做法

拆成 7 个端口，适配器只实现自己真正具备的：

| 端口 | 职责 |
|---|---|
| `ChannelPaymentPort` | 下单 |
| `ChannelQueryPort` | 主动查单 |
| `ChannelCapturePort` | 请款（海外两段式） |
| `ChannelVoidPort` | 撤销授权（解冻未请款的额度） |
| `ChannelReversePort` | 撤销交易（国内特色，海外无对应物） |
| `ChannelClosePort` | 关闭未支付订单 |
| `ChannelRefundPort` / `ChannelRefundQueryPort` | 退款与退款查询 |

注册表 `ChannelGatewayRegistry` 返回 `Optional`：

```java
gatewayRegistry.capturePortOf(ChannelCode.WECHAT_PAY)   // Optional.empty() —— 微信不支持请款
```

为空就说明这家通道不支持，上层据此返回明确错误。

## 1.4 「能不能接」与「选哪家」要分开

这是本工程刻意做的一处职责切分：

| | `ChannelCapabilityRegistry` | `ChannelRoutingService` |
|---|---|---|
| 回答 | **能不能接** | **选哪家** |
| 输入 | 能力需求 | 能力需求 + 费率 + 健康度 + 已尝试记录 |
| 性质 | 纯规则、无状态、极稳定 | 策略，会随业务演进 |
| 位置 | `domain/channel/service` | `domain/payment/service` |

拆开之后，路由策略可以独立演进（今天按成本优先，明天按成功率优先，后天加灰度），而能力匹配规则保持稳定。

反过来，混在一起会怎样？路由类会越来越大，最后既改不动也不敢测 —— **这是支付中台最常见的腐化起点**。

## 1.5 适配器的职责边界

适配器只做四件事：

1. **翻译请求**：`ChannelRequest` → 通道专属报文（微信的 `out_trade_no` / Stripe 的 `amount`）
2. **签名与传输**：按各家的 `AuthModel` 构造签名、发 HTTPS
3. **翻译响应**：通道响应 → 归一化的 `ChannelResult`（含原始状态）
4. **映射状态码**：各家的状态字符串 → `ChannelRawStatus`

**适配器绝不能做的三件事**：

1. **不能修改领域状态**。状态变更一律由 `PaymentOrder` 自己完成。
2. **不能吞掉超时**。网络超时必须转成 `FailureInfo.unknown(...)`，而不是抛异常或返回失败。超时意味着结果未知，直接判失败会造成「用户付了钱，订单是失败的」。
3. **不能自行生成幂等键**。必须用领域层传入的 `request.idempotencyKey()`。

## 1.6 一个请求的完整流转

以「创建支付」为例，看各层各做了什么：

```
① interfaces.PaymentController
   协议转换：JSON → CreatePaymentCommand
   取 Idempotency-Key 请求头
   ✗ 不做任何业务判断

② application.PaymentApplicationService
   加载商户 → 校验准入
   接口幂等（IdempotencyStore）
   业务幂等（findByMerchantOrderNo）
   通道路由 → 建单 → 保存 → 发事件
   下发通道 → 应用结果 → 保存 → 发事件
   ✗ 不含业务规则

③ domain.PaymentOrder
   校验状态转移是否合法（PaymentStateMachine）
   创建/复用 PaymentAttempt（保住幂等键）
   应用通道结果 → 推进状态 → 登记领域事件
   ✓ 业务规则全在这里

④ infrastructure.WeChatPayAdapter
   ChannelRequest → 微信报文
   商户证书签名 → HTTPS
   微信响应 → ChannelResult（含 NOTPAY 原始状态）
   ✗ 不碰领域状态
```

## 1.7 检查清单

评审这个工程（或任何六边形工程）时，可以按这几条卡：

- [ ] domain 下能否 grep 到 `org.springframework`？（应该没有）
- [ ] domain 下能否 grep 到 `java.sql` / `HttpClient`？（应该没有）
- [ ] 聚合根的属性 setter 是不是都是 private？（应该是）
- [ ] 适配器里有没有出现 `order.setStatus(...)`？（不应该有）
- [ ] 新增一家通道，业务代码是否需要改动？（不应该需要）
- [ ] 领域层的单测是否需要启动 Spring？（不应该需要）
