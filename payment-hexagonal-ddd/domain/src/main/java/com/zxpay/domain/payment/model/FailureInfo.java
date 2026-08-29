package com.zxpay.domain.payment.model;

/**
 * 失败信息。
 *
 * <p>支付领域里「失败」不是一个布尔值，而是一组决定后续动作的属性：
 * <ul>
 *   <li><b>能否重试</b>（{@code retryable}）：通道抖动可以重试；用户余额不足重试一万次也没用，
 *       只会白白消耗通道配额并让用户体验更差。</li>
 *   <li><b>能否换通道</b>（{@code switchable}）：通道故障值得切换；风控拦截换哪家都会被拦，
 *       切了只是把拒绝率平摊到别的通道上，还会污染健康度指标。</li>
 *   <li><b>结果是否未知</b>（{@link FailureCategory#UNKNOWN}）：<b>这是最危险的一类</b>。</li>
 * </ul>
 *
 * <h3>为什么 UNKNOWN 必须单独成类</h3>
 * <p>调用通道超时，不代表用户没被扣款——很可能通道已经扣款成功，
 * 只是响应没回到我们这里。如果此时直接把订单置为 FAILED，就会出现
 * <b>「用户付了钱，订单是失败的」</b>，这是支付系统最严重的事故类型之一。
 *
 * <p>正确处理：UNKNOWN 一律<b>先查单</b>，以通道侧的真实状态为准；
 * 查单也失败则进入待确认队列，由补偿任务持续重试，绝不提前置终态。
 */
public record FailureInfo(
        /** 通道原始错误码。必须保留，用于运营定位与差排处理。 */
        String code,

        /** 可读的错误描述。 */
        String message,

        FailureCategory category,

        /** 同一通道重试是否有意义。 */
        boolean retryable,

        /** 切换其他通道是否有意义。 */
        boolean switchable
) {

    public enum FailureCategory {
        /** 业务原因：余额不足、限额、卡已过期。重试无意义。 */
        BUSINESS,

        /** 风控拦截。换通道同样会被拦，切换无意义。 */
        RISK,

        /** 参数错误或商户配置问题。属于我方 bug 或配置缺失，需告警。 */
        INVALID_REQUEST,

        /** 通道不可用：超时、5xx、连接失败。可重试，可切换。 */
        CHANNEL_UNAVAILABLE,

        /** 通道维护窗口。可延后重试，可切换。 */
        CHANNEL_MAINTENANCE,

        /** 幂等冲突：同键不同参数。需查单确认，不可盲目重试。 */
        IDEMPOTENCY_CONFLICT,

        /** 结果未知：超时或响应不可解析。必须先查单，禁止直接置终态。 */
        UNKNOWN,
    }

    public static FailureInfo business(String code, String message) {
        return new FailureInfo(code, message, FailureCategory.BUSINESS, false, true);
    }

    public static FailureInfo risk(String code, String message) {
        return new FailureInfo(code, message, FailureCategory.RISK, false, false);
    }

    public static FailureInfo invalidRequest(String code, String message) {
        return new FailureInfo(code, message, FailureCategory.INVALID_REQUEST, false, false);
    }

    public static FailureInfo channelUnavailable(String code, String message) {
        return new FailureInfo(code, message, FailureCategory.CHANNEL_UNAVAILABLE, true, true);
    }

    public static FailureInfo maintenance(String code, String message) {
        return new FailureInfo(code, message, FailureCategory.CHANNEL_MAINTENANCE, true, true);
    }

    public static FailureInfo idempotencyConflict(String code, String message) {
        return new FailureInfo(code, message, FailureCategory.IDEMPOTENCY_CONFLICT, false, false);
    }

    public static FailureInfo unknown(String code, String message) {
        return new FailureInfo(code, message, FailureCategory.UNKNOWN, true, false);
    }

    /** 是否必须先查单才能决定下一步。 */
    public boolean requiresQueryBeforeDecision() {
        return category == FailureCategory.UNKNOWN || category == FailureCategory.IDEMPOTENCY_CONFLICT;
    }
}
