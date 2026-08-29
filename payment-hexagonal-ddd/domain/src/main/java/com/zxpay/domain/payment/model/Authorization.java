package com.zxpay.domain.payment.model;

import com.zxpay.sharedkernel.money.Money;

import java.time.Duration;
import java.time.Instant;

/**
 * 授权信息：用户额度已被冻结、但资金尚未划走的那段中间态。
 *
 * <p>这是海外卡支付的核心概念，国内支付体系里没有直接对应物。
 * 如果领域模型里没有这个对象，就无法正确表达「钱被冻了但还没扣」，
 * 只能退化成「成功/失败」二值，从而在请款环节丢失关键状态。
 *
 * <p>三个必须关注的点：
 * <ol>
 *   <li><b>授权会过期</b>。典型 7 天。过期后额度自动释放，请款必然失败。
 *       酒店、租车这类「先授权后实际消费」的业务，最容易在这里出事：
 *       用户住完店，商户请款时授权已失效。</li>
 *   <li><b>请款金额不得超过授权金额</b>（部分通道允许加油/小费场景上浮约 15%）。
 *       想多收钱必须走增量授权（{@code INCREMENTAL_AUTH}），不是随便改个数就行。</li>
 *   <li><b>未请款的授权要撤销用 VOID，不是 REFUND</b>。
 *       这是新手最常见的错误：钱没扣却去调退款接口，必然报错或产生错误的账务记录。</li>
 * </ol>
 */
public record Authorization(
        /** 通道侧的授权标识（Stripe 的 PaymentIntent id、卡组的 auth code）。 */
        String channelAuthorizationId,

        /** 已授权金额。 */
        Money authorizedAmount,

        /** 授权发生时间。 */
        Instant authorizedAt,

        /** 授权失效时间。为 null 表示通道未给出明确有效期，需按保守策略处理。 */
        Instant expiresAt,

        /** 网络令牌（Apple Pay / Google Pay 场景），可用于后续无卡扣款。 */
        String networkToken
) {

    public Authorization {
        if (authorizedAmount == null) {
            throw new IllegalArgumentException("authorizedAmount must not be null");
        }
    }

    public static Authorization of(String authorizationId, Money amount, Instant authorizedAt, Instant expiresAt) {
        return new Authorization(authorizationId, amount, authorizedAt, expiresAt, null);
    }

    /** 默认授权有效期（卡组织常见值）。通道未明确给出时使用。 */
    public static final Duration DEFAULT_TTL = Duration.ofDays(7);

    public Instant effectiveExpiresAt() {
        return expiresAt != null ? expiresAt : authorizedAt.plus(DEFAULT_TTL);
    }

    public boolean isExpiredAt(Instant now) {
        return !now.isBefore(effectiveExpiresAt());
    }

    /** 请款金额是否在授权额度内。 */
    public boolean covers(Money captureAmount) {
        return !captureAmount.isGreaterThan(authorizedAmount);
    }

    /** 授权后剩余可请款金额。 */
    public Money remaining(Instant now) {
        return isExpiredAt(now) ? Money.zero(authorizedAmount.currency()) : authorizedAmount;
    }
}
