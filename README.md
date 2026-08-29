# PayDemo

支付系统 DDD 演示仓库，包含两个独立子工程：

- `payment/` —— 早期版本（包名 com.payment）
- `payment-ddd-demo/` —— 完整 DDD 分层版：国内(微信/支付宝/京东)+国外(PayPal/Apple Pay/Antom/Worldpay/Stripe) 8 渠道防腐层适配、回调链路闭环（留痕/验签/幂等/事务内事件/AFTER_COMMIT 上游通知/指数退避重试/掉单补偿）、对账上下文。设计文档见 `payment-ddd-demo/docs/architecture-design.md`
