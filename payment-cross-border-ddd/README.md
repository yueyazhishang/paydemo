# payment-ddd-demo

**研究 DDD 在跨境支付系统上的应用**——代码是论证载体，文档是主要交付物。

92 个 Java 文件 / 7300 行，9 个国内外通道，7 个 Maven 模块，31 个单元测试全部通过。

---

## 三份文档的分工

| 文档 | 回答什么问题 | 适合什么时候看 |
|---|---|---|
| **[DDD-CROSS-BORDER.md](DDD-CROSS-BORDER.md)** ⭐ | **跨境支付多出来的复杂度是什么？DDD 的哪些工具能吃掉它，哪些不能？** | **先读这份** |
| [ARCHITECTURE.md](ARCHITECTURE.md) | 这套代码怎么分层、怎么抽象、每个决策的 trade-off 是什么 | 想看懂代码时读 |
| 本文 | 模块结构、阅读路径、如何验证 | 现在 |

**如果时间有限，只读 `DDD-CROSS-BORDER.md`。** 那份文档讨论的是方法论，不依赖代码。

---

## 模块结构

```
payment-ddd-demo
├── payment-shared-kernel      共享内核：Money / Currency / DomainEvent   ← 零外部依赖
├── payment-domain             领域层：收单聚合 / 状态机 / 通道能力模型      ← 零外部依赖
├── payment-application        应用层：用例编排 / 幂等四层 / Outbox
├── payment-channel-adapter    通道适配层：9 个通道归一化                  ← 零外部依赖
├── payment-infrastructure     基础设施层：仓储 / 幂等存储 / 定时任务
├── payment-interfaces         接入层：REST API / 回调入口
├── payment-bootstrap          启动器
└── tools/                     代码生成脚本（本次生成用，可忽略）
```

三个模块标注"零外部依赖"是刻意的：领域层不该被框架污染，这样领域模型才能被独立验证。

---

## 针对"研究 DDD"的阅读路径

如果目标是研究方法论，而不是读代码，按这个顺序看：

**① 先看 DDD-CROSS-BORDER.md 的第一、二章**
搞清楚跨境支付相比国内支付的复杂度增量（3~5 倍概念膨胀，但有聚类结构），以及这些结构如何对应到限界上下文。

**② 然后看战略设计部分（2.2 节）**
核心域 / 支撑域 / 通用域的判定。这是 DDD 战略设计里最实用、也最常被跳过的一步——它的价值不是分类，是**决定资源投放在哪里**。

**③ 关键反直觉结论**：通道适配代码量最大，但它是通用域。
真正的竞争力在通道路由——同样接 9 个通道，能否把每一笔交易路由到"成功率最高 + 成本最低"的那个。

**④ 用代码验证一个具体论点：Apple Pay**
看 `ApplePayAdapter.java`（委托适配器）+ `ChannelCapabilityTest` 里的三个测试：

```
applePayDelegatesToAcquirer      → 它必须寄生于收单行
applePayRejectsUnsupportedDelegate → 不能委托给微信（微信不支持它）
applePayDelegateCanBeSwitched    → 换委托目标即可容灾
```

这个案例的通用教训：**当某物的能力全部来自另一物时，它是关系而非实体。错把它建模成实体，会失去整个维度的灵活性。**

**⑤ 看两个真实的建模缺陷**
`DDD-CROSS-BORDER.md` 第 7.3 节记录了本次开发中真实出现、且都是测试全绿后才暴露的两个缺陷：

| 缺陷 | 症状 | 为何隐蔽 |
|---|---|---|
| `requestRefund` 把 `expireAt != null` 作为退款期限校验前置条件 | 未设过期时间的订单永久跳过期限校验 | 单测全绿，功能自测正常，直到"超期退款"请求到来 |
| `reconstitute` 未回填 `createdAt`（构造时写死 `Instant.now()`） | 从 DB 重建的历史订单，创建时间被替换成当前时间，所有依赖账龄的校验失效 | 同上 |

**错误的概念比错误的代码更昂贵——代码错误会崩溃，概念错误只会静默地产生错误的钱。**

---

## 验证

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/openjdk-24.0.1/Contents/Home
mvn clean test
```

预期：7 个模块 BUILD SUCCESS，31 个测试通过。

其中最有价值的 31 个断言分布在：

| 测试类 | 数量 | 覆盖 |
|---|---|---|
| `MoneyTest` | 7 | KWD 三位小数、JPY 零小数、跨币种相加拒绝、分账余数 |
| `PaymentOrderTest` | 11 | 超额退款防护、回调乱序、金额篡改、退款期限 |
| `PaymentStateMachineTest` | 6 | 终态不可变、禁止回退、两段式路径 |
| `ChannelCapabilityTest` | 14 | Apple Pay 委托、能力矩阵差异、路由硬过滤 |

构造顺序不是"先写实现再补测试"，而是**先想清楚会出什么事故，再写用例**。每个用例名对应一类真实的资金安全事故。

---

## 环境说明

- 编译用 JDK 24（本机只有 1.8 和 24），目标字节码 `release=17`
- Maven 3.9+，Spring Boot 3.3.4
- 基础设施层全部是内存实现（Map + ConcurrentHashMap + ReentrantLock），便于零依赖跑通
- 生产替换指引写在各实现类的 Javadoc 里（搜 "生产替换" / "生产环境必须"）
