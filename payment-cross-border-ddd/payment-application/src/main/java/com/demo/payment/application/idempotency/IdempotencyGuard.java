package com.demo.payment.application.idempotency;

import com.demo.payment.shared.exception.IdempotencyConflictException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 幂等守卫 —— 支付系统的第一道防线。
 *
 * <h3>支付系统需要四层幂等，缺一不可</h3>
 * <ol>
 *   <li><b>接入层幂等</b>（本类）：客户端传 {@code Idempotency-Key}，
 *       防止用户重复点击、网络重试导致重复下单。</li>
 *   <li><b>业务层幂等</b>：{@code (merchant_id, merchant_order_no)} 唯一索引。
 *       这是最后兜底 —— 即使客户端没传幂等键，也不能产生两笔单。</li>
 *   <li><b>通道层幂等</b>：outTradeNo 每次尝试唯一 + 通道幂等键。
 *       防止重复扣款，这是<b>资金安全级别</b>的幂等。</li>
 *   <li><b>回调层幂等</b>：notifyId 去重 + 状态机终态守卫。
 *       通道会重投通知，必须去重；乱序到达时必须拒绝非法状态回退。</li>
 * </ol>
 *
 * <p>这四层分别防的是：用户手抖 / 客户端 bug / 网络重试 / 通道重投。
 * 任何一层缺失，都会在某个特定场景产生重复扣款。
 */
public class IdempotencyGuard {

    private static final Duration DEFAULT_TTL = Duration.ofHours(24);

    private final IdempotencyStore store;

    public IdempotencyGuard(IdempotencyStore store) {
        this.store = store;
    }

    /**
     * 执行带幂等保护的业务逻辑。
     *
     * @param key          幂等键
     * @param fingerprint  请求指纹（由关键业务参数计算）
     * @param business     真正的业务逻辑
     * @param serializer   结果序列化器（用于缓存首次结果）
     */
    public <T> T execute(String key, String fingerprint,
                         Supplier<T> business,
                         java.util.function.Function<T, String> serializer,
                         java.util.function.Function<String, T> deserializer) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey is required");
        }

        // 步骤一：原子抢占
        Optional<IdempotencyRecord> existing = store.tryAcquire(key, fingerprint, DEFAULT_TTL);

        if (existing.isPresent()) {
            IdempotencyRecord record = existing.get();

            // 幂等键相同但参数不同 → 客户端 bug，必须暴露
            if (!record.matches(fingerprint)) {
                throw new IdempotencyConflictException(key,
                        "幂等键 " + key + " 已用于不同的请求参数，请更换幂等键");
            }

            return switch (record.status()) {
                // 上一次已完成，直接返回缓存的结果（这是幂等的核心价值）
                case COMPLETED -> deserializer.apply(record.resultSnapshot());
                // 上一次还在处理中 —— 返回明确异常让调用方稍后重试，绝不能并发执行业务逻辑
                case PROCESSING -> throw new IllegalStateException(
                        "请求正在处理中，请勿重复提交. key=" + key);
                // 上一次失败，允许重试
                case FAILED -> runAndRecord(key, business, serializer);
            };
        }

        return runAndRecord(key, business, serializer);
    }

    private <T> T runAndRecord(String key, Supplier<T> business,
                               java.util.function.Function<T, String> serializer) {
        try {
            T result = business.get();
            store.complete(key, serializer.apply(result));
            return result;
        } catch (RuntimeException e) {
            store.fail(key);
            throw e;
        }
    }

    /**
     * 计算请求指纹。
     *
     * <p><b>只应包含"决定业务结果"的参数</b>：金额、币种、商户号、商户订单号、支付方式。
     * 不应包含：请求时间、traceId、用户 IP —— 这些每次都变，会导致指纹永远不匹配，
     * 幂等失效。
     */
    public static String fingerprint(String... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                md.update((part == null ? "" : part).getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0x1F); // 分隔符，防止 "ab"+"c" 与 "a"+"bc" 碰撞
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** 生成一个默认 TTL 的过期时间 */
    public static Instant defaultExpireAt() {
        return Instant.now().plus(DEFAULT_TTL);
    }
}
