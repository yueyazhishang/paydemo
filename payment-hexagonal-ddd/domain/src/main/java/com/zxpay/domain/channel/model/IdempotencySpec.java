package com.zxpay.domain.channel.model;

import java.time.Duration;

/**
 * 通道侧幂等规范。
 *
 * <p>支付系统里幂等有三层，通道层是第三层，也最容易被忽略：
 * <ol>
 *   <li><b>接口层</b>：商户调用我们时带 Idempotency-Key，防商户重试造成重复下单。</li>
 *   <li><b>业务层</b>：{@code (merchant_id, merchant_order_no)} 唯一索引，防同一笔业务重复生成支付单。</li>
 *   <li><b>通道层</b>：本对象描述的维度，防我们重试时通道重复扣款。</li>
 * </ol>
 *
 * <p>关键差异：
 * <ul>
 *   <li>国内通道幂等键就是<b>商户订单号</b>（微信 out_trade_no、支付宝 out_trade_no），
 *       语义强：同一订单号再次下单，返回的是原订单而不是新单。</li>
 *   <li>Stripe 的幂等键是<b>请求头</b> {@code Idempotency-Key}（有效期 24 小时），
 *       且要求同键同参数——同键不同参数会被拒绝。这意味着重试必须复用<b>完全相同</b>的
 *       请求体和同一个 key，不能每次重试都新生成。</li>
 *   <li>PayPal 用 {@code PayPal-Request-Id}，性质类似 Stripe。</li>
 * </ul>
 *
 * <p>因此幂等键必须由<b>领域层生成并持久化</b>（见 {@code PaymentAttempt#idempotencyKey}），
 * 而不是在 HTTP 客户端里随机生成后丢弃。一旦丢弃，重试就一定重复扣款。
 */
public record IdempotencySpec(
        /** 幂等键的落点维度。 */
        IdempotencyScope scope,

        /** 通道保留幂等记录的时长。超出后重试不再被保护。 */
        Duration retention,

        /** 同键不同参数时通道的行为。 */
        ConflictBehaviour conflictBehaviour,

        String note
) {

    public enum IdempotencyScope {
        /** 幂等键即商户订单号，通道以此为主键。国内主流。 */
        MERCHANT_ORDER_NO,

        /** 独立请求头，与订单号解耦。Stripe / PayPal。 */
        REQUEST_HEADER,
    }

    public enum ConflictBehaviour {
        /** 返回原请求的结果（最友好，重试绝对安全）。 */
        RETURN_ORIGINAL,

        /** 直接报错，重试方需先查单再决定是否重试。 */
        REJECT,

        /** 行为未定义，必须靠我方主动查单兜底。 */
        UNDEFINED,
    }
}
