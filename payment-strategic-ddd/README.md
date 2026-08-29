# payment-strategic-ddd

**DDD 战略设计驱动的支付核心域建模**。与同级 `payment` module 的差异不在于功能多少，而在于**设计起点不同**。

## 与 `payment` module 的区别

| 维度 | `payment` | `payment-strategic-ddd`（本 module） |
|---|---|---|
| 设计起点 | 战术层：四层架构 + 聚合/值对象 | 战略层：子域划分 → 限界上下文 → 上下文映射 → 发布语言 |
| "限界上下文" | 包目录（`domain/payment`、`domain/refund`），同处一个模块，可自由 import | Maven 模块，**编译期强制隔离**，想越界都编译不过 |
| 子域划分 | 无，三个上下文平起平坐 | 收单=核心域（最高设计密度），退款/对账/通道=支撑域 |
| 跨上下文引用 | 共享同一领域模型 | 只经发布语言（`PaymentSucceededV1`），下游自行翻译 |
| 共享内核 | 整个 `domain` 包 | 仅 `Money` / 事件基类 / `ChannelCode` |
| 通道数量 | 8+ 个通道适配器 | 2 个通道接口设计（微信 v3、Stripe），重点在抽象而非覆盖 |
| 目标 | 功能可运行 | **架构正确性可评审**（不追求跑通） |

一句话概括：`payment` 示范"DDD 的代码长什么样"，本 module 示范"**为什么这么切分**"。

## 模块结构

```
payment-strategic-ddd/
├── shared-kernel/                 # 极小共享内核（Money / 事件基类 / ChannelCode / 集成事件）
│
├── acquisition-context/           # 收单上下文【核心域】
│   ├── acquisition-domain/        #   Payment 聚合根、PaymentAttempt 实体、状态机、领域事件
│   └── acquisition-application/   #   对外用例边界
│
├── refund-context/                # 退款上下文【支撑域】
│   ├── refund-domain/             #   Refund 聚合根、PaidFact 值对象
│   └── refund-application/
│       └── acl/                   #   防腐层：PaymentSucceededTranslator（入站翻译）
│
├── reconciliation-context/        # 对账上下文【支撑域】（骨架）
├── channel-gateway-context/       # 通道网关上下文【支撑域 · 防腐层】
│   ├── channel-domain/            #   ChannelPort（对称 ACL：出站 + 入站）、ChannelCapability
│   └── channel-infrastructure/    #   适配器（未实现）
│
├── bootstrap/                     # 启动与装配（唯一允许依赖所有上下文的模块）
├── docs/
│   ├── 01-战略设计.md              # 子域划分、上下文映射、发布语言契约
│   └── 02-收单核心域设计.md         # 状态机、聚合边界、并发控制、幂等、超时关单、Outbox
└── pom.xml
```

## 核心设计决策

### 1. 限界上下文的物理边界由编译器强制

`refund-domain/pom.xml` 只声明了 `shared-kernel` 依赖，**没有** `acquisition-domain`。

验证方式——在退款域里写一句 `import com.zx.payment.acquisition.domain.model.Payment;`，编译直接失败：

```
[ERROR] 程序包 com.zx.payment.acquisition.domain.model 不存在
[ERROR] 找不到符号: 类 Payment
```

边界不再靠"团队约定"和 code review 维持，而是**编译期事实**。

### 2. 同一个词，不同上下文有不同模型

"支付"在收单上下文是**活的聚合根**（会 CREATED→PAYING→SUCCESS 迁移，有状态机和不变量）；
在退款上下文是**死的事实快照** `PaidFact`（不可变，只有数据没有行为）。

两者同名但语义不同——这正是限界上下文存在的意义。退款上下文拿 `paymentId` 字符串引用支付单，
用 `PaidFact` 判断可退性，永远不 import 收单的 `Payment`。

翻译发生在**下游**（`refund-application/acl/PaymentSucceededTranslator`），符合防腐层原则：谁需要，谁翻译。

### 3. 收单聚合的设计要点

- **状态机**：6 态，补上了 `PARTIAL`（部分支付）和 `FAILED` 可重试（换通道挽回成功率）
- **聚合边界**：`PaymentAttempt` 在聚合内（"不超收""不并发下单"是强一致不变量），
  通道调用流水在聚合外（技术日志，无限增长，无业务不变量依赖）
- **并发控制**：状态机幂等 + 乐观锁，**两者缺一不可**。状态机挡不住 ABA 问题
  （读到旧状态 → 被别人改了两次 → 写回覆盖）。量化对比下乐观锁比分布式锁快约 25 倍
- **幂等三层**：创建幂等（merchantOrderNo 唯一）、状态幂等（终态返回 false）、尝试幂等（attempt 层）
- **事件发布**：必须走 Outbox。事务内同步发布会导致"通知发了但事务回滚"的资损事故
- **超时关单**：事件驱动注册延迟任务（精度到秒）+ 定时扫描兜底（防任务丢失）

详见 `docs/02-收单核心域设计.md`。

## 构建与验证

```bash
cd payment-strategic-ddd
mvn clean install
```

22 个领域层测试（15 收单 + 7 退款），零框架依赖，毫秒级执行：

```bash
mvn test
```

测试即文档——`PaymentTest` 的用例列表就是这个聚合守护的全部不变量。

## 当前进度

| 部分 | 状态 |
|---|---|
| 战略设计（子域/上下文/映射/契约） | ✅ 完成，见 `docs/01-战略设计.md` |
| 收单核心域（Payment + Attempt + 状态机 + 并发控制） | ✅ 完成，含 15 个测试 |
| 退款上下文（PaidFact + Refund + 防腐层翻译） | ✅ 完成，含 7 个测试 |
| 通道网关端口（对称 ACL + 能力矩阵） | ✅ 端口设计完成 |
| 微信 v3 / Stripe 适配器 | ⬜ 未实现（重复劳动，非架构重点） |
| 对账上下文 | ⬜ 骨架 |
| bootstrap / REST 接口 | ⬜ 未实现（本 module 定位是架构样板，不追求跑通） |
| 基础设施层（仓储实现、Outbox 投递） | ⬜ 未实现 |

## 技术栈

Java 17 / Maven 3.8+ ，领域层零框架依赖（无 Spring、无 ORM 注解）。
