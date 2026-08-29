package com.zxpay.domain.payment.model;

import com.zxpay.domain.channel.model.ChannelCode;

import java.time.Instant;
import java.util.Optional;

/**
 * 支付尝试（{@code PaymentOrder} 聚合内部实体）。
 *
 * <p>记录「对某一家通道的一次完整调用」及其全部痕迹。
 *
 * <h3>为什么必须单独成实体</h3>
 * <ol>
 *   <li><b>保住通道幂等键</b>。{@code idempotencyKey} 一次生成、随尝试持久化。
 *       同通道重试时复用它，通道才会认出「这是同一笔请求」。
 *       如果每次重试新生成一个 key，通道会当成新交易——<b>重复扣款就是这么来的</b>。</li>
 *   <li><b>留住失败通道的交易号</b>。A 通道下单超时（UNKNOWN），切到 B 通道支付成功。
 *       事后对账发现 A 通道其实也扣了款。如果没有这次尝试的记录，
 *       这笔悬空扣款就永远找不回来。</li>
 *   <li><b>支撑通道质量分析</b>。成功率、耗时、失败原因分布，都需要尝试级粒度。
 *       只看订单级数据，无法区分「首次失败后切换成功」和「一次就成功」。</li>
 * </ol>
 *
 * <h3>重试 vs 切换</h3>
 * <ul>
 *   <li><b>重试</b>：同一通道再试一次 → <b>复用</b>当前 attempt（幂等键不变）。</li>
 *   <li><b>切换</b>：换一家通道 → <b>新建</b> attempt，旧 attempt 标记
 *       {@link AttemptStatus#SWITCHED_OUT} 但记录完整保留。</li>
 * </ul>
 */
public final class PaymentAttempt {

    private final PaymentAttemptId attemptId;
    private final ChannelCode channel;
    private final int attemptNo;

    /** 通道幂等键。整个尝试生命周期内恒定。 */
    private final String idempotencyKey;

    /** 发给通道的订单号。首单用商户订单号，切通道后加序号后缀避免跨通道冲突。 */
    private final String channelOrderNo;

    private AttemptStatus status;
    private ChannelRawStatus lastRawStatus;
    private String channelTransactionId;
    private FailureInfo failure;
    private Authorization authorization;
    private ChannelInteraction interaction;

    private Instant createdAt;
    private Instant submittedAt;
    private Instant respondedAt;

    public PaymentAttempt(PaymentAttemptId attemptId, ChannelCode channel, int attemptNo,
                          String idempotencyKey, String channelOrderNo, Instant createdAt) {
        this.attemptId = attemptId;
        this.channel = channel;
        this.attemptNo = attemptNo;
        this.idempotencyKey = idempotencyKey;
        this.channelOrderNo = channelOrderNo;
        this.status = AttemptStatus.CREATED;
        this.createdAt = createdAt;
    }

    // ---------- 读取 ----------

    public PaymentAttemptId attemptId() { return attemptId; }
    public ChannelCode channel() { return channel; }
    public int attemptNo() { return attemptNo; }
    public String idempotencyKey() { return idempotencyKey; }
    public String channelOrderNo() { return channelOrderNo; }
    public AttemptStatus status() { return status; }
    public String channelTransactionId() { return channelTransactionId; }
    public Instant submittedAt() { return submittedAt; }
    public Instant respondedAt() { return respondedAt; }

    public Optional<ChannelRawStatus> lastRawStatus() { return Optional.ofNullable(lastRawStatus); }
    public Optional<FailureInfo> failure() { return Optional.ofNullable(failure); }
    public Optional<Authorization> authorization() { return Optional.ofNullable(authorization); }
    public Optional<ChannelInteraction> interaction() { return Optional.ofNullable(interaction); }

    // ---------- 行为 ----------

    /** 标记为已下发。重复下发（并发保护）由聚合根拦截，此处只做状态推进。 */
    public void markSubmitted(Instant at) {
        this.status = AttemptStatus.SUBMITTED;
        this.submittedAt = at;
    }

    /**
     * 应用通道响应。
     *
     * <p>无论成功失败都完整留存原始状态与交易号，绝不因为失败就清空信息。
     */
    public void applyResult(ChannelResult result, Instant at) {
        this.respondedAt = at;
        this.lastRawStatus = result.rawStatus();
        if (result.channelTransactionId() != null) {
            this.channelTransactionId = result.channelTransactionId();
        }
        if (result.interaction() != null) {
            this.interaction = result.interaction();
        }
        result.authorizationOptional().ifPresent(auth -> this.authorization = auth);
        result.failureOptional().ifPresent(f -> this.failure = f);

        this.status = switch (result.normalizedStatus()) {
            case SUCCEEDED -> AttemptStatus.SUCCEEDED;
            case AUTHORIZED -> AttemptStatus.SUCCEEDED;
            case FAILED -> result.requiresQueryBeforeDecision()
                    ? AttemptStatus.UNKNOWN
                    : AttemptStatus.FAILED;
            default -> AttemptStatus.SUBMITTED;
        };
    }

    /** 标记为结果未知。超时场景专用，后续必须查单。 */
    public void markUnknown(FailureInfo failure, Instant at) {
        this.status = AttemptStatus.UNKNOWN;
        this.failure = failure;
        this.respondedAt = at;
    }

    /** 标记为已切换放弃。 */
    public void markSwitchedOut(Instant at) {
        this.status = AttemptStatus.SWITCHED_OUT;
        this.respondedAt = respondedAt == null ? at : respondedAt;
    }

    public boolean isTerminal() {
        return status.isTerminal();
    }

    /** 是否能复用本次尝试重试（幂等键未变，通道侧安全）。 */
    public boolean canRetry() {
        return status.retryable();
    }

    /** 是否已拿到通道交易号（用于对账与后续退款）。 */
    public boolean hasTransactionId() {
        return channelTransactionId != null && !channelTransactionId.isBlank();
    }
}
