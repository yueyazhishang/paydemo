package com.demo.payment.domain.channel.spi;

import com.demo.payment.domain.channel.model.*;
import com.demo.payment.domain.channel.model.ChannelCapability;

/**
 * 通道统一端口（Port）—— 所有通道适配器必须实现的 SPI。
 *
 * <h3>设计决策：能力声明 + 操作，而不是"大而全接口 + UnsupportedOperationException"</h3>
 * <p>常见反模式：接口定义 10 个方法，不支持的实现抛 {@code UnsupportedOperationException}。
 * 这等于<b>把能力差异从编译期推迟到运行期</b>，上线才炸。
 * 本设计改为：接口方法保持精简，能力差异通过 {@link ChannelCapability} 在<b>编译期声明</b>，
 * 上层调用前先查能力，路由阶段就能过滤掉不支持的通道。
 *
 * <h3>为什么用 Port/Adapter 而不是简单继承</h3>
 * <p>这是六边形架构的关键：本接口定义在 <b>domain 层</b>，实现在 <b>channel-adapter 层</b>，
 * 依赖方向是 <i>adapter → domain</i>，即<b>实现依赖抽象</b>。
 * 领域层完全不知道微信、Stripe 的存在 —— 这样领域模型才能被独立测试、独立演进。
 *
 * <h3>关于返回值</h3>
 * <p>所有方法返回<b>结果对象</b>而非抛异常。原因：通道异常是<b>正常业务流的一部分</b>
 * （余额不足、风控拦截、银行超时），不是程序错误。用异常表达会导致
 * 事务回滚时机难以控制，且容易把"支付失败"和"系统故障"混为一谈。
 * 只有<b>基础设施故障</b>（网络不可达、证书缺失）才允许抛 {@link ChannelInfrastructureException}，
 * 由上层决定是否重试/切通道。
 */
public interface PaymentChannelPort {

    /** 通道编码，用于路由与注册中心索引 */
    ChannelCode channelCode();

    /**
     * <b>能力矩阵声明</b> —— 这是本接口最重要的方法。
     * 路由、退款校验、状态机分支全部依赖它，而不是依赖 {@code if (channel == X)}。
     */
    ChannelCapability capability();

    /**
     * 发起支付。
     *
     * <p><b>幂等要求</b>：实现方必须保证同一 {@code command.merchantTradeNo()} 重复调用
     * 不会重复扣款。策略由 {@code capability().idempotencyMode()} 决定：
     * <ul>
     *   <li>{@code HEADER_IDEMPOTENCY_KEY}：把幂等键放进请求头（Stripe）。</li>
     *   <li>{@code BUSINESS_FIELD}：把幂等键放进业务字段（Antom 的 paymentRequestId）。</li>
     *   <li>{@code MERCHANT_ORDER_NO_ONLY}：无通道侧幂等，靠商户订单号唯一约束兜底。
     *       此时<b>重试前必须先查单</b>，否则有重复扣款风险（微信/支付宝的坑）。</li>
     * </ul>
     *
     * @return 支付受理结果（注意："受理成功" ≠ "支付成功"，国内通道返回的是支付凭证）
     */
    PayResponse pay(PayCommand command);

    /**
     * 主动查证。<b>这是资金安全的最后一道防线</b>。
     *
     * <p>异步回调可能丢失、延迟、乱序，甚至被伪造。生产系统的正确姿势是：
     * <b>回调只当作"去查一下"的触发器，状态以主动查证结果为准</b>。
     */
    QueryResponse query(QueryCommand command);

    /** 关闭交易（仅对未支付订单生效）。用于订单超时、用户取消。 */
    CloseResponse close(CloseCommand command);

    /** 退款。支持部分退款的通道需校验累计退款额不超过原额。 */
    RefundResponse refund(RefundCommand command);

    /**
     * 撤销（void）—— 与退款的本质区别：<b>撤销发生在清算前，不产生独立退款单，
     * 资金原路返回且通常不计手续费</b>。仅卡收单通道支持。
     */
    CancelResponse cancel(CancelCommand command);

    /**
     * 请款（capture）—— 两段式通道的第二步。
     * 授权时会冻结买家额度，请款才真正划账。酒店预授权、先发货后扣款都依赖它。
     */
    CaptureResponse capture(CaptureCommand command);

    /**
     * <b>回调归一化</b> —— 把千奇百怪的通道报文（JSON / XML / form-urlencoded / JWT）
     * 统一解析并验签为内部结构。验签失败必须抛异常，绝不能吞掉。
     */
    NotificationParseResult parseNotification(RawNotification raw);

    /** 该通道在给定上下文下的健康度评分（0~100），用于智能路由与熔断 */
    default int healthScore(RoutingContext context) {
        return 100;
    }
}
