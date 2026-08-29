# PayDemo

支付系统 DDD 演示仓库，包含五个部分：

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
