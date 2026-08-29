package com.zxpay.domain.payment.model;

import com.zxpay.domain.channel.model.ChannelCode;
import com.zxpay.domain.merchant.model.MerchantAppId;
import com.zxpay.domain.merchant.model.MerchantId;
import com.zxpay.domain.payment.event.PaymentEvents;
import com.zxpay.domain.payment.service.IdempotencyKeyFactory;
import com.zxpay.domain.payment.service.PaymentStateMachine;
import com.zxpay.sharedkernel.exception.DomainException;
import com.zxpay.sharedkernel.model.AggregateRoot;
import com.zxpay.sharedkernel.money.Money;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 支付单聚合根 —— 支付上下文的一致性边界。
 *
 * <p><b>为什么 PaymentAttempt 在聚合内、RefundOrder 在聚合外？</b>
 *
 * <p>这是本模型最重要的一条聚合设计判断，也是面试里最值得展开讲的点：
 *
 * <ul>
 *   <li><b>尝试必须在聚合内</b>。因为存在强不变量：
 *       「同一时刻，同一通道只能有一个进行中的尝试」。
 *       违反它会导致同一通道并发发起两次下单，而幂等键只能保护同键请求，
 *       两次独立下单会各自生成交易——典型的重复扣款。
 *       要守住这条不变量，就必须在一个事务边界内修改，
 *       所以尝试是聚合内部实体，随支付单一起加载和保存。</li>
 *
 *   <li><b>退款必须在聚合外</b>。因为不存在跨退款单的强不变量需要事务保证：
 *       退款的约束是「累计退款不超过实付」，这条靠
 *       {@code refundedAmount} 这一个数值字段 + 乐观锁就够了，
 *       不需要把退款单装箱进支付单。
 *       如果强行内嵌，支付单会随着退款次数不断膨胀，
 *       每次退款都要加载整个聚合，性能与并发度都会崩。</li>
 * </ul>
 *
 * <p>一句话总结聚合边界的判断标准：
 * <b>按不变量划边界，不按「看起来像父子关系」划边界。</b>
 */
public final class PaymentOrder extends AggregateRoot<PaymentOrderId> {

    // ---------- 标识与不可变业务要素 ----------
    private final PaymentOrderId id;
    private final MerchantId merchantId;
    private final MerchantAppId appId;
    private final String merchantOrderNo;
    private final PaymentInstruction instruction;
    private final Instant createdAt;
    private final Instant expireAt;

    // ---------- 可变状态 ----------
    private PaymentStatus status;
    private ChannelCode currentChannel;
    private final List<PaymentAttempt> attempts = new ArrayList<>();

    private Authorization authorization;
    private String channelTransactionId;

    private Money paidAmount;
    private Money refundedAmount;
    private Money refundingAmount;

    private Instant paidAt;
    private Instant updatedAt;
    private String closeReason;
    private String lastFailureCode;
    private String lastFailureMessage;

    private int captureSeq = 0;
    private int partialRefundCount = 0;

    private PaymentOrder(PaymentOrderId id, MerchantId merchantId, MerchantAppId appId,
                         String merchantOrderNo, PaymentInstruction instruction, Instant now) {
        this.id = id;
        this.merchantId = merchantId;
        this.appId = appId;
        this.merchantOrderNo = merchantOrderNo;
        this.instruction = instruction;
        this.createdAt = now;
        this.expireAt = now.plus(instruction.expiry());
        this.status = PaymentStatus.CREATED;
        this.paidAmount = Money.zero(instruction.amount().currency());
        this.refundedAmount = Money.zero(instruction.amount().currency());
        this.refundingAmount = Money.zero(instruction.amount().currency());
        this.updatedAt = now;
    }

    // ================= 工厂 =================

    /**
     * 创建支付单。
     *
     * <p>幂等不在聚合内保证——「同一商户订单号只能有一张单」是跨实例的约束，
     * 靠数据库唯一索引 {@code (app_id, merchant_order_no)} 兜底，
     * 应用层负责先查后建。聚合内强行校验需要访问仓储，会让聚合依赖端口，
     * 破坏领域层的纯粹性。
     */
    public static PaymentOrder create(PaymentOrderId id,
                                      MerchantId merchantId,
                                      MerchantAppId appId,
                                      String merchantOrderNo,
                                      PaymentInstruction instruction,
                                      Instant now) {
        if (merchantOrderNo == null || merchantOrderNo.isBlank()) {
            throw new DomainException("MERCHANT_ORDER_NO_REQUIRED", "merchantOrderNo must not be blank");
        }
        PaymentOrder order = new PaymentOrder(id, merchantId, appId, merchantOrderNo, instruction, now);
        order.registerEvent(new PaymentEvents.PaymentOrderCreated(
                id, appId, merchantOrderNo, instruction.amount(), instruction.paymentMethod()));
        return order;
    }

    // ================= 通道路由 =================

    public void assignChannel(ChannelCode channel, Instant now) {
        PaymentStateMachine.requireTransition(status, PaymentStatus.ROUTING);
        this.currentChannel = channel;
        this.status = PaymentStatus.ROUTING;
        this.updatedAt = now;
        registerEvent(new PaymentEvents.PaymentRouted(id, channel));
    }

    /** 路由失败：无任何可用通道。 */
    public void markRoutingFailed(String code, String message, Instant now) {
        PaymentStateMachine.requireTransition(status, PaymentStatus.FAILED);
        this.status = PaymentStatus.FAILED;
        this.lastFailureCode = code;
        this.lastFailureMessage = message;
        this.updatedAt = now;
        registerEvent(new PaymentEvents.PaymentFailed(id, appId, merchantOrderNo, null, code, message));
    }

    // ================= 通道尝试 =================

    /**
     * 开始（或复用）一次通道尝试。
     *
     * <p><b>这是防止重复扣款的关键方法。</b>
     *
     * <p>规则：
     * <ul>
     *   <li>同一通道已有可重试的尝试 → <b>复用</b>，保住原幂等键，重试安全。</li>
     *   <li>换一家通道 → 旧尝试标记 {@code SWITCHED_OUT}（记录保留），
     *       新建尝试，幂等键由（订单号，通道）确定性推导。</li>
     * </ul>
     */
    public PaymentAttempt beginAttempt(ChannelCode channel, Instant now) {
        if (channel == null) {
            throw new DomainException("CHANNEL_REQUIRED", "channel must not be null");
        }
        if (status.isTerminal()) {
            throw new DomainException("PAYMENT_TERMINAL",
                    "cannot start attempt on terminal payment " + id.value() + " with status " + status);
        }

        boolean switching = currentChannel != null && currentChannel != channel;

        Optional<PaymentAttempt> reusable = attempts.stream()
                .filter(a -> a.channel() == channel && a.canRetry())
                .findFirst();

        PaymentAttempt attempt;
        boolean retryOfSameChannel;

        if (reusable.isPresent()) {
            attempt = reusable.get();
            retryOfSameChannel = true;
        } else {
            if (switching) {
                switchChannel(channel, "manual-or-fallback-switch", now);
            }
            int attemptNo = attempts.size() + 1;
            attempt = new PaymentAttempt(
                    PaymentAttemptId.generate(),
                    channel,
                    attemptNo,
                    IdempotencyKeyFactory.channelPaymentKey(id, channel),
                    channelOrderNoFor(attemptNo),
                    now);
            attempts.add(attempt);
            retryOfSameChannel = false;
        }

        this.currentChannel = channel;
        transitionTo(PaymentStatus.PAYING, now);
        attempt.markSubmitted(now);
        registerEvent(new PaymentEvents.PaymentAttemptStarted(
                id, attempt.attemptId(), channel, attempt.attemptNo(), retryOfSameChannel));
        return attempt;
    }

    /** 切换到另一家通道。旧尝试记录完整保留，只标记放弃。 */
    public void switchChannel(ChannelCode newChannel, String reason, Instant now) {
        ChannelCode from = currentChannel;
        attempts.stream()
                .filter(a -> a.channel() != newChannel && !a.isTerminal())
                .forEach(a -> a.markSwitchedOut(now));
        this.currentChannel = newChannel;
        if (from != null && from != newChannel) {
            registerEvent(new PaymentEvents.ChannelSwitched(id, from, newChannel, reason, attempts.size() + 1));
        }
    }

    /**
     * 生成发给通道的订单号。
     *
     * <p>首次尝试直接用商户订单号（国内通道以此幂等，语义最清晰）；
     * 切换通道后加序号后缀，避免不同通道间出现相同单号导致的对账歧义。
     */
    private String channelOrderNoFor(int attemptNo) {
        return attemptNo == 1 ? merchantOrderNo : merchantOrderNo + "-" + attemptNo;
    }

    // ================= 通道结果应用 =================

    /**
     * 应用通道结果，返回本次应用的处理结果。
     *
     * <p>不抛异常：回调场景下抛异常会导致通道无限重试（详见
     * {@link ChannelResultApplication} 的说明）。
     */
    public ChannelResultApplication applyChannelResult(ChannelResult result, Instant now) {
        Optional<PaymentAttempt> attemptOpt = attemptOf(result.attemptId());
        if (attemptOpt.isEmpty()) {
            return ChannelResultApplication.IGNORED_DUPLICATE;
        }
        PaymentAttempt attempt = attemptOpt.get();
        attempt.applyResult(result, now);
        this.updatedAt = now;

        // 终态订单收到通道结果：区分「重复通知」与「关闭后付款成功」
        if (status.isTerminal()) {
            if (result.isSucceeded()) {
                // 钱扣了，订单却是终态 —— 必须触发补偿
                return ChannelResultApplication.TERMINAL_CONFLICT_PAID_AFTER_CLOSE;
            }
            return ChannelResultApplication.IGNORED_TERMINAL;
        }

        if (result.isSucceeded()) {
            return applySuccess(result, now);
        }
        if (result.isAuthorized()) {
            return applyAuthorized(result, now);
        }
        if (result.isFailed()) {
            return applyFailure(result, now);
        }
        return applyPending(result, now);
    }

    private ChannelResultApplication applySuccess(ChannelResult result, Instant now) {
        Money amount = result.paidAmount() != null ? result.paidAmount() : instruction.amount();

        // 金额校验：通道实付与订单金额不符时必须拦下，绝不能默默入账
        if (amount.currency() != instruction.amount().currency()
                || amount.isGreaterThan(instruction.amount())) {
            return ChannelResultApplication.AMOUNT_MISMATCH;
        }

        this.paidAmount = amount;
        this.paidAt = result.paidAt() != null ? result.paidAt() : now;
        this.channelTransactionId = result.channelTransactionId();
        this.lastFailureCode = null;
        this.lastFailureMessage = null;
        transitionTo(PaymentStatus.SUCCEEDED, now);
        registerEvent(new PaymentEvents.PaymentSucceeded(
                id, appId, merchantOrderNo, result.channel(),
                result.channelTransactionId(), amount, paidAt));
        return ChannelResultApplication.APPLIED;
    }

    private ChannelResultApplication applyAuthorized(ChannelResult result, Instant now) {
        if (result.authorization() == null) {
            return ChannelResultApplication.UNKNOWN_NEEDS_QUERY;
        }
        this.authorization = result.authorization();
        transitionTo(PaymentStatus.AUTHORIZED, now);
        registerEvent(new PaymentEvents.PaymentAuthorized(
                id, result.channel(), result.authorization().channelAuthorizationId(),
                result.authorization().authorizedAmount(), result.authorization().effectiveExpiresAt()));
        return ChannelResultApplication.APPLIED;
    }

    private ChannelResultApplication applyFailure(ChannelResult result, Instant now) {
        FailureInfo failure = result.failure();
        if (failure != null) {
            this.lastFailureCode = failure.code();
            this.lastFailureMessage = failure.message();
        }

        // 结果未知：保持中间态，等主动查单，绝不提前置失败
        if (failure != null && failure.requiresQueryBeforeDecision()) {
            return ChannelResultApplication.UNKNOWN_NEEDS_QUERY;
        }

        // 可切换的失败不落终态：留给应用层切到备用通道
        // （是否真的切换由应用层决定，聚合只负责不提前判死）
        if (failure != null && failure.switchable() && hasRemainingCandidates()) {
            return ChannelResultApplication.APPLIED;
        }

        transitionTo(PaymentStatus.FAILED, now);
        registerEvent(new PaymentEvents.PaymentFailed(
                id, appId, merchantOrderNo, result.channel(),
                failure == null ? null : failure.code(),
                failure == null ? null : failure.message()));
        return ChannelResultApplication.APPLIED;
    }

    private ChannelResultApplication applyPending(ChannelResult result, Instant now) {
        PaymentStatus target = result.normalizedStatus() == PaymentStatus.USERPAYING
                ? PaymentStatus.USERPAYING
                : PaymentStatus.PAYING;
        if (status != target) {
            transitionTo(target, now);
        }
        return ChannelResultApplication.APPLIED;
    }

    // ================= 请款（海外 auth 模式） =================

    /**
     * 发起请款。返回本次请款序号，调用方据此生成请款幂等键。
     *
     * <p>校验链条：状态必须是已授权 → 授权未过期 → 请款金额不超过授权额。
     * 少任何一条，都会在通道侧失败，而那时用户额度已冻结，体验极差。
     */
    public int requestCapture(Money amount, Instant now) {
        if (status != PaymentStatus.AUTHORIZED) {
            throw new DomainException("CAPTURE_STATUS_INVALID",
                    "capture requires AUTHORIZED status, but was " + status);
        }
        if (authorization == null) {
            throw new DomainException("AUTHORIZATION_MISSING", "no authorization recorded for " + id.value());
        }
        if (authorization.isExpiredAt(now)) {
            throw new DomainException("AUTHORIZATION_EXPIRED",
                    "authorization expired at " + authorization.effectiveExpiresAt() + " for " + id.value());
        }
        if (!authorization.covers(amount)) {
            throw new DomainException("CAPTURE_AMOUNT_EXCEEDED",
                    "capture amount " + amount + " exceeds authorized " + authorization.authorizedAmount());
        }

        captureSeq++;
        transitionTo(PaymentStatus.CAPTURING, now);
        registerEvent(new PaymentEvents.CaptureRequested(id, currentChannel, amount));
        return captureSeq;
    }

    /** 应用请款结果。 */
    public ChannelResultApplication applyCaptureResult(ChannelResult result, Instant now) {
        if (result.isSucceeded()) {
            this.paidAmount = result.paidAmount() != null ? result.paidAmount() : instruction.amount();
            this.paidAt = result.paidAt() != null ? result.paidAt() : now;
            this.channelTransactionId = result.channelTransactionId();
            transitionTo(PaymentStatus.SUCCEEDED, now);
            registerEvent(new PaymentEvents.PaymentCaptured(
                    id, result.channel(), paidAmount, result.channelTransactionId()));
            return ChannelResultApplication.APPLIED;
        }

        // 请款失败：授权若仍在有效期内，退回已授权态允许重新请款
        if (authorization != null && !authorization.isExpiredAt(now)) {
            transitionTo(PaymentStatus.AUTHORIZED, now);
            return ChannelResultApplication.APPLIED;
        }
        transitionTo(PaymentStatus.FAILED, now);
        return ChannelResultApplication.APPLIED;
    }

    /** 撤销授权后关闭订单。钱没扣，因此不是退款。 */
    public void markVoided(Instant now) {
        if (status != PaymentStatus.AUTHORIZED) {
            throw new DomainException("VOID_STATUS_INVALID",
                    "void requires AUTHORIZED status, but was " + status);
        }
        transitionTo(PaymentStatus.CLOSED, now);
        this.closeReason = "VOIDED";
        registerEvent(new PaymentEvents.PaymentClosed(id, appId, merchantOrderNo, "VOIDED"));
    }

    // ================= 关单与超时 =================

    /**
     * 关闭订单。
     *
     * <p><b>已支付的订单不允许关闭</b>——要终止必须走退款。
     * 这条不变量靠状态机强制：{@code SUCCEEDED -> CLOSED} 不在合法转移表中。
     */
    public void close(String reason, Instant now) {
        if (status.isPaid()) {
            throw new DomainException("PAID_ORDER_CANNOT_BE_CLOSED",
                    "paid order cannot be closed directly, use refund instead: " + id.value());
        }
        transitionTo(PaymentStatus.CLOSED, now);
        this.closeReason = reason;
        registerEvent(new PaymentEvents.PaymentClosed(id, appId, merchantOrderNo, reason));
    }

    public boolean isExpired(Instant now) {
        return !status.isTerminal() && !now.isBefore(expireAt);
    }

    /** 超时关单。已支付的单不会被超时误伤（状态机会拦截）。 */
    public boolean expireIfNeeded(Instant now) {
        if (!isExpired(now)) {
            return false;
        }
        if (status.isPaid()) {
            return false;
        }
        transitionTo(PaymentStatus.CLOSED, now);
        this.closeReason = "EXPIRED";
        registerEvent(new PaymentEvents.PaymentClosed(id, appId, merchantOrderNo, "EXPIRED"));
        return true;
    }

    // ================= 退款协同（由退款上下文驱动） =================

    /**
     * 登记一笔退款已受理（进入退款中）。
     *
     * <p>这里只做金额占用，不做退款单管理——退款单是独立聚合。
     * 占用 {@code refundingAmount} 是为了让并发的多笔部分退款互不超额：
     * 「已退 + 退款中 + 本次」不得超过实付。
     */
    public void reserveRefund(Money amount, Instant now) {
        if (amount.isGreaterThan(remainingRefundable())) {
            throw new DomainException("REFUND_AMOUNT_EXCEEDED",
                    "refund amount " + amount + " exceeds remaining refundable " + remainingRefundable());
        }
        this.refundingAmount = refundingAmount.plus(amount);
        transitionTo(PaymentStatus.REFUNDING, now);
    }

    /** 退款成功：占用转已退，并推进订单状态。 */
    public void applyRefundSucceeded(Money amount, Instant now) {
        this.refundingAmount = refundingAmount.minus(amount);
        this.refundedAmount = refundedAmount.plus(amount);
        if (amount.isLessThan(instruction.amount())) {
            partialRefundCount++;
        }
        PaymentStatus target = refundedAmount.compareTo(paidAmount) >= 0
                ? PaymentStatus.REFUNDED
                : PaymentStatus.PARTIAL_REFUNDED;
        transitionTo(target, now);
        this.updatedAt = now;
    }

    /** 退款失败：释放占用，退回原状态。 */
    public void applyRefundFailed(Money amount, Instant now) {
        this.refundingAmount = refundingAmount.minus(amount);
        if (refundingAmount.isZero()) {
            PaymentStatus target = refundedAmount.isZero() ? PaymentStatus.SUCCEEDED : PaymentStatus.PARTIAL_REFUNDED;
            transitionTo(target, now);
        }
        this.updatedAt = now;
    }

    /** 仍可退金额 = 实付 - 已退 - 退款中。 */
    public Money remainingRefundable() {
        return paidAmount.minus(refundedAmount).minus(refundingAmount);
    }

    public boolean isFullyRefunded() {
        return !paidAmount.isZero() && refundedAmount.compareTo(paidAmount) >= 0;
    }

    // ================= 内部工具 =================

    private void transitionTo(PaymentStatus target, Instant now) {
        PaymentStateMachine.requireTransition(status, target);
        this.status = target;
        this.updatedAt = now;
    }

    /**
     * 是否还有未尝试过的候选通道。
     *
     * <p>聚合不应该知道全部通道列表（那是路由服务的职责），
     * 因此这里用一个保守判断：若本次是首次尝试且失败可切换，
     * 就认为仍值得让应用层去试其他通道。
     * 应用层会结合真实候选列表做最终决定。
     */
    private boolean hasRemainingCandidates() {
        return attempts.size() == 1;
    }

    // ================= 读取 =================

    @Override
    public PaymentOrderId id() { return id; }

    public MerchantId merchantId() { return merchantId; }
    public MerchantAppId appId() { return appId; }
    public String merchantOrderNo() { return merchantOrderNo; }
    public PaymentInstruction instruction() { return instruction; }
    public PaymentStatus status() { return status; }
    public ChannelCode currentChannel() { return currentChannel; }
    public Instant createdAt() { return createdAt; }
    public Instant expireAt() { return expireAt; }
    public Instant paidAt() { return paidAt; }
    public Instant updatedAt() { return updatedAt; }
    public String closeReason() { return closeReason; }
    public String lastFailureCode() { return lastFailureCode; }
    public String lastFailureMessage() { return lastFailureMessage; }
    public int partialRefundCount() { return partialRefundCount; }
    public int captureSeq() { return captureSeq; }

    public Money amount() { return instruction.amount(); }
    public Money paidAmount() { return paidAmount; }
    public Money refundedAmount() { return refundedAmount; }
    public Money refundingAmount() { return refundingAmount; }

    public Optional<Authorization> authorization() { return Optional.ofNullable(authorization); }
    public Optional<String> channelTransactionId() { return Optional.ofNullable(channelTransactionId); }

    public List<PaymentAttempt> attempts() { return Collections.unmodifiableList(attempts); }

    public Optional<PaymentAttempt> attemptOf(PaymentAttemptId attemptId) {
        return attempts.stream()
                .filter(a -> a.attemptId().equals(attemptId))
                .findFirst();
    }

    /** 当前进行中的尝试。用于重试与查单。 */
    public Optional<PaymentAttempt> currentAttempt() {
        if (currentChannel == null) {
            return Optional.empty();
        }
        return attempts.stream()
                .filter(a -> a.channel() == currentChannel && !a.isTerminal())
                .reduce((first, second) -> second);
    }

    /** 是否已有过指向该通道的尝试。 */
    public boolean hasAttempted(ChannelCode channel) {
        return attempts.stream().anyMatch(a -> a.channel() == channel);
    }
}
