# 支付系统 Demo - DDD实现

## 项目概述

基于领域驱动设计(DDD)的多通道支付系统演示，支持国内外主流支付通道，采用分层架构设计。

## 支持的支付通道

### 国内通道
| 通道编码 | 说明 | 状态 |
|------|------|------|
| WECHAT_JSAPI | 微信JSAPI支付 | ✅ |
| WECHAT_NATIVE | 微信Native支付 | ✅ |
| WECHAT_H5 | 微信H5支付 | ✅ |
| WECHAT_APP | 微信APP支付 | ✅ |
| WECHAT_MINI | 微信小程序支付 | ✅ |
| ALIPAY_PC | 支付宝电脑网站支付 | ✅ |
| ALIPAY_WAP | 支付宝手机网站支付 | ✅ |
| ALIPAY_APP | 支付宝APP支付 | ✅ |
| ALIPAY_FACE_TO_FACE | 支付宝当面付 | ✅ |
| JDPAY_EBANK | 京东网银支付 | ✅ |
| JDPAY_QUICK | 京东快捷支付 | ✅ |

### 国际通道
| 通道编码 | 说明 | 状态 |
|------|------|------|
| PAYPAL | PayPal | ✅ |
| APPLE_PAY | Apple Pay | ✅ |
| STRIPE | Stripe | ✅ |
| STRIPE_ALIPAY | Stripe Alipay | ✅ |
| STRIPE_WECHAT | Stripe WeChat Pay | ✅ |
| ADYEN | Adyen (原Antom) | ✅ |
| WORLDPAY | Worldpay | ✅ |
| UNIONPAY_INTL | 银联国际 | ✅ |

## DDD 架构设计

```
┌─────────────────────────────────────────────────────────────┐
│                      接口层 (Interfaces)                      │
│  REST Controllers │ Webhook Handlers │ DTOs/VOs              │
├─────────────────────────────────────────────────────────────┤
│                      应用层 (Application)                     │
│  PaymentAppService │ RefundAppService │ ChannelAppService     │
├─────────────────────────────────────────────────────────────┤
│                      领域层 (Domain)                          │
│  Aggregates │ Entities │ Value Objects │ Domain Services     │
│  Repository Interfaces │ Domain Events                      │
├─────────────────────────────────────────────────────────────┤
│                   基础设施层 (Infrastructure)                  │
│  Repository Impl │ Channel Adapters │ Message Queue │ Cache  │
└─────────────────────────────────────────────────────────────┘
```

## 限界上下文 (Bounded Contexts)

### 1. 支付核心上下文 (Payment Core)
- **聚合根**: PaymentOrder
- **实体**: PaymentTransaction
- **值对象**: PaymentId, OrderId, Money
- **领域服务**: PaymentDomainService
- **仓储接口**: PaymentOrderRepository

### 2. 退款上下文 (Refund)
- **聚合根**: RefundOrder
- **值对象**: RefundId
- **领域服务**: RefundDomainService
- **仓储接口**: RefundOrderRepository

### 3. 渠道管理上下文 (Channel Management)
- **枚举**: ChannelCode
- **适配器**: PaymentChannelAdapter
- **注册表**: ChannelAdapterRegistry

## 核心领域模型

```
PaymentOrder (支付订单) - 聚合根
├── paymentId: PaymentId (值对象)
├── merchantOrderId: OrderId (值对象)
├── amount: Money (值对象)
├── refundedAmount: Money (值对象)
├── status: PaymentStatus (枚举)
├── channelCode: ChannelCode (枚举)
├── transactions: List<PaymentTransaction> (实体集合)
├── extraParams: Map<String, String>
├── createdAt: LocalDateTime
└── 领域行为:
    ├── initiatePayment() - 发起支付
    ├── processPaymentSuccess() - 支付成功
    ├── processPaymentFailure() - 支付失败
    ├── close() - 关闭订单
    └── addRefundedAmount() - 添加退款金额

RefundOrder (退款订单) - 聚合根
├── refundId: RefundId (值对象)
├── paymentId: PaymentId (引用)
├── refundAmount: Money (值对象)
├── status: RefundStatus (枚举)
└── 领域行为:
    ├── startProcessing() - 开始处理
    ├── markSuccess() - 退款成功
    └── markFailed() - 退款失败
```

## 设计模式应用

### 适配器模式 (Adapter Pattern)
通过 `PaymentChannelAdapter` 接口统一不同支付通道的实现：

```java
public interface PaymentChannelAdapter {
    ChannelCreateResult createPayment(PaymentOrder order);
    ChannelQueryResult queryPayment(String channelOrderId);
    ChannelRefundResult refund(RefundOrder refundOrder);
    boolean verifyWebhook(WebhookRequest request);
}
```

### 策略模式 (Strategy Pattern)
根据渠道编码自动选择对应的适配器实现：

```java
PaymentChannelAdapter adapter = channelAdapterRegistry.getAdapter(ChannelCode.WECHAT_JSAPI);
```

### 防腐层 (Anti-Corruption Layer)
适配器将外部支付系统的特定概念转换为领域模型，隔离外部变化。

## 快速开始

### 环境要求
- JDK 17+
- Maven 3.8+

### 构建运行
```bash
mvn clean package
mvn spring-boot:run
```

### API示例

#### 1. 创建支付订单
```bash
POST /api/payments
Content-Type: application/json

{
    "merchantId": "M001",
    "orderId": "ORDER_20260829_001",
    "userId": "USER_001",
    "amount": 99.99,
    "currency": "CNY",
    "channelCode": "WECHAT_JSAPI",
    "description": "测试商品",
    "notifyUrl": "https://your-domain.com/webhook/wechat",
    "returnUrl": "https://your-domain.com/return",
    "extra": {
        "openid": "oUpF8uMuAJO_M2pxb1Q9zNjWeS6o"
    }
}
```

响应:
```json
{
    "paymentId": "PAY20260829123456000001",
    "orderId": "ORDER_20260829_001",
    "amount": "99.99",
    "currency": "CNY",
    "status": "PENDING",
    "statusDesc": "待支付",
    "channelCode": "WECHAT_JSAPI",
    "channelName": "微信JSAPI支付",
    "paymentParams": {
        "appId": "wx...",
        "timeStamp": "1693286400",
        "nonceStr": "...",
        "package": "prepay_id=wx...",
        "signType": "RSA",
        "paySign": "..."
    },
    "expireTime": "2026-08-29T12:34:56",
    "createdAt": "2026-08-29T12:04:56"
}
```

#### 2. 查询支付结果
```bash
GET /api/payments/PAY20260829123456000001
```

#### 3. 同步支付状态
```bash
POST /api/payments/PAY20260829123456000001/sync
```

#### 4. 关闭支付订单
```bash
POST /api/payments/PAY20260829123456000001/close
```

#### 5. 申请退款
```bash
POST /api/payments/PAY20260829123456000001/refunds
Content-Type: application/json

{
    "refundAmount": 99.99,
    "reason": "用户申请退款",
    "notifyUrl": "https://your-domain.com/webhook/refund"
}
```

#### 6. 获取支持的通道列表
```bash
GET /api/channels
GET /api/channels/domestic
GET /api/channels/international
```

## Webhook 回调

各通道异步通知URL:
- 微信支付: `/api/webhook/wechat`
- 支付宝: `/api/webhook/alipay`
- Stripe: `/api/webhook/stripe`
- PayPal: `/api/webhook/paypal`
- Adyen: `/api/webhook/adyen`
- Worldpay: `/api/webhook/worldpay`

## 项目结构

```
src/main/java/com/payment/
├── PaymentApplication.java              # 启动类
├── domain/                              # 领域层
│   ├── payment/                         # 支付上下文
│   │   ├── model/
│   │   │   ├── aggregate/               # 聚合根
│   │   │   │   └── PaymentOrder.java
│   │   │   ├── entity/                  # 实体
│   │   │   │   └── PaymentTransaction.java
│   │   │   ├── valueobject/             # 值对象
│   │   │   │   ├── Money.java
│   │   │   │   ├── OrderId.java
│   │   │   │   └── PaymentId.java
│   │   │   └── enums/                   # 枚举
│   │   │       └── PaymentStatus.java
│   │   ├── service/                     # 领域服务
│   │   │   └── PaymentDomainService.java
│   │   ├── repository/                  # 仓储接口
│   │   │   └── PaymentOrderRepository.java
│   │   └── event/                       # 领域事件
│   │       ├── PaymentCreatedEvent.java
│   │       └── PaymentSuccessEvent.java
│   ├── refund/                          # 退款上下文
│   │   ├── model/
│   │   │   └── aggregate/
│   │   │       └── RefundOrder.java
│   │   ├── service/
│   │   │   └── RefundDomainService.java
│   │   └── repository/
│   │       └── RefundOrderRepository.java
│   └── channel/                         # 渠道上下文
│       └── model/
│           └── enums/
│               └── ChannelCode.java
├── application/                         # 应用层
│   └── payment/
│       ├── dto/                         # DTO
│       │   ├── CreatePaymentRequest.java
│       │   ├── CreatePaymentResponse.java
│       │   ├── PaymentQueryResponse.java
│       │   └── RefundRequest.java
│       └── PaymentAppService.java        # 应用服务
├── infrastructure/                      # 基础设施层
│   ├── channel/
│   │   ├── ChannelAdapterRegistry.java  # 通道注册表
│   │   └── adapter/
│   │       ├── PaymentChannelAdapter.java  # 适配器接口
│   │       └── impl/                    # 通道适配器实现
│   │           ├── WechatPayAdapter.java
│   │           ├── AlipayAdapter.java
│   │           ├── JDPayAdapter.java
│   │           ├── PayPalAdapter.java
│   │           ├── ApplePayAdapter.java
│   │           ├── StripeAdapter.java
│   │           ├── AdyenAdapter.java
│   │           └── WorldpayAdapter.java
│   └── persistence/                     # 持久化实现
│       └── PaymentOrderJpaRepository.java
└── interfaces/                          # 接口层
    ├── rest/                            # REST控制器
    │   ├── PaymentController.java
    │   └── ChannelController.java
    ├── webhook/                         # Webhook处理
    │   └── WebhookController.java
    └── config/                          # 配置
        ├── PaymentConfig.java
        └── GlobalExceptionHandler.java
```

## DDD 设计要点

### 1. 聚合根 (Aggregate Root)
- **PaymentOrder**: 维护支付订单的完整生命周期，封装业务规则
- **RefundOrder**: 管理退款流程

### 2. 值对象 (Value Object)
- **Money**: 封装金额和货币，不可变
- **OrderId/PaymentId/RefundId**: 类型安全的ID封装

### 3. 领域服务 (Domain Service)
- 处理跨聚合的业务逻辑
- 如创建支付订单时的唯一性检查

### 4. 仓储接口 (Repository Interface)
- 定义在领域层，实现在基础设施层
- 依赖倒置原则

### 5. 防腐层 (Anti-Corruption Layer)
- 支付通道适配器隔离外部系统变化
- 统一接口封装不同通道的差异

## 后续扩展

- [ ] 分布式事务处理 (Saga模式)
- [ ] 幂等性保证 (基于唯一键去重)
- [ ] 风控系统集成
- [ ] 多币种支持优化
- [ ] 分期付款支持
- [ ] 订阅支付支持
- [ ] 支付路由(智能选择最优通道)
- [ ] 通道健康检查和熔断
- [ ] 对账系统
- [ ] 商户管理模块

## 许可证

MIT License
