# PayDemo

支付系统 DDD 演示仓库，包含六个部分：

- `payment-hexagonal-ddd/` —— **六边形架构 + 通道能力矩阵版**（9 渠道）：端口与适配器落地、能力差异建模为数据（一个能力一个端口）、聚合边界论证（attempt 在内 / refund 在外）、确定性幂等键、状态机集中管理、归一化与原始状态双轨保留。5 个限界上下文、6 篇设计文档（含 15 条 ADR）。设计文档见 `payment-hexagonal-ddd/docs/`
- `payment/` —— 早期版本（包名 com.payment）
- `payment-ddd-demo/` —— 完整 DDD 分层版：国内(微信/支付宝/京东)+国外(PayPal/Apple Pay/Antom/Worldpay/Stripe) 8 渠道防腐层适配、回调链路闭环（留痕/验签/幂等/事务内事件/AFTER_COMMIT 上游通知/指数退避重试/掉单补偿）、对账上下文。设计文档见 `payment-ddd-demo/docs/architecture-design.md`
- `payment-strategic-ddd/` —— 按限界上下文分模块版：acquisition / channel-gateway / refund / reconciliation + shared-kernel，侧重战略设计的落地形态
- `modules/payment-ddd/` —— 跨境支付聚合器 DDD research（artifactId `payment-core`）：PayPal/Stripe 适配器示例、内存事件总线、RefundSaga 流程管理器。**偏战术设计演示**（聚合、领域事件、Saga 的具体写法）。详见 `modules/payment-ddd/DESIGN.md`
- `payment-cross-border-ddd/` —— **跨境支付 DDD 应用研究**（9 渠道，含银联；7 个 Maven 模块）

## 关于两个跨境工程的分工

`modules/payment-ddd` 与 `payment-cross-border-ddd` 同属跨境方向，但**侧重不同、可互补**：

| | `modules/payment-ddd` | `payment-cross-border-ddd` |
|---|---|---|
| 侧重 | **战术设计**——聚合/值对象/领域事件/Saga 怎么写 | **战略设计**——上下文怎么切、资源怎么投 |
| 通道 | Stripe + PayPal（2 个，作为示例） | 9 个（含银联），重点在**能力矩阵归一化** |
| 主交付物 | `DESIGN.md`（时序、上下文、trade-off） | `DDD-CROSS-BORDER.md`（跨境五个领域深水区、核心域判定） |
| 形态 | 单模块 | 7 个 Maven 模块，domain/channel-adapter 零外部依赖 |

如果只想看 DDD 具体怎么写代码，读 `modules/payment-ddd`；
如果想研究"跨境相比国内多出来的复杂度到底是什么、DDD 哪些工具能吃掉它"，读 `payment-cross-border-ddd/DDD-CROSS-BORDER.md`。

## 新增：`payment-hexagonal-ddd` 的定位

与 `payment-cross-border-ddd` 同样覆盖 9 个渠道，但**切入角度不同**：

| | `payment-cross-border-ddd` | `payment-hexagonal-ddd` |
|---|---|---|
| 主线 | 跨境 vs 国内的**领域复杂度**研究 | **六边形架构**（端口/适配器）如何落地 |
| 通道差异的表达 | 能力矩阵归一化 | 能力矩阵 + **端口隔离**（一个能力一个端口，适配器只实现自己具备的） |
| 依赖约束 | domain/channel-adapter 零外部依赖 | 同上，且 **`interfaces` 编译期看不到适配器**（Maven `runtime` 作用域强制） |
| 聚合设计 | 侧重上下文切分 | 侧重**聚合边界的论证**：为什么 attempt 在内、refund 在外 |
| 工程细节深度 | 领域深水区分析 | 幂等键确定性生成、状态机守卫、乱序通知、终态冲突补偿 |
| 文档 | `DDD-CROSS-BORDER.md` | 6 篇，其中 `06-adr.md` 记录 15 条决策的**理由与被否决方案** |

如果想研究"通道差异该怎么抽象才不会写出 if-else 沼泽"，读 `payment-hexagonal-ddd`；
如果更关心"跨境业务本身比国内多出来的复杂度"，读 `payment-cross-border-ddd`。
