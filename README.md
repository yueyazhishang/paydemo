# PayDemo

支付系统 DDD 演示仓库，包含四个独立子工程：

- `payment/` —— 早期版本（包名 com.payment）
- `payment-ddd-demo/` —— 完整 DDD 分层版：国内(微信/支付宝/京东)+国外(PayPal/Apple Pay/Antom/Worldpay/Stripe) 8 渠道防腐层适配、回调链路闭环（留痕/验签/幂等/事务内事件/AFTER_COMMIT 上游通知/指数退避重试/掉单补偿）、对账上下文。设计文档见 `payment-ddd-demo/docs/architecture-design.md`
- `payment-strategic-ddd/` —— 按限界上下文分模块版：acquisition / channel-gateway / refund / reconciliation + shared-kernel，侧重战略设计的落地形态
- `payment-cross-border-ddd/` —— **跨境支付 DDD 研究**（9 渠道，含银联；7 个 Maven 模块）。定位与前三个工程不同：重点是方法论研究而非系统实现，主交付物是 `payment-cross-border-ddd/DDD-CROSS-BORDER.md` —— 跨境相比国内多出的五个领域深水区（多币种与汇率、合规、长事务、争议拒付、时区日切）、核心域/支撑域/通用域判定、上下文映射模式选型。代码作为论证载体，7 模块编译通过、31 个单元测试全绿。
