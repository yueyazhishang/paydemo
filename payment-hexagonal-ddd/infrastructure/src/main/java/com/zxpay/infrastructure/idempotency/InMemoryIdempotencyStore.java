package com.zxpay.infrastructure.idempotency;

import com.zxpay.application.port.out.IdempotencyStore;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 接口幂等存储的内存实现。
 *
 * <p>两点必须注意：
 *
 * <ol>
 *   <li><b>分布式环境下不能用内存实现。</b>
 *       商户的重试请求很可能落到另一台机器上，本地 Map 拦不住，
 *       幂等直接失效。生产必须用 Redis，且「判断 + 写入」要原子
 *       （{@code SETNX} 或 Lua 脚本）。分成两步做，
 *       两个并发的同键请求会同时通过检查。</li>
 *   <li><b>要保存首次处理的结果快照</b>，重复请求直接返回它。
 *       若第二次返回不同的订单号，商户侧照样会乱——
 *       「幂等」不只是「不重复执行」，还包括「返回结果一致」。</li>
 * </ol>
 */
@Component
public class InMemoryIdempotencyStore implements IdempotencyStore {

    private final Map<String, Instant> locks = new ConcurrentHashMap<>();
    private final Map<String, StoredResult> results = new ConcurrentHashMap<>();

    @Override
    public synchronized boolean tryAcquire(String key, Duration ttl) {
        Instant now = Instant.now();
        Instant existing = locks.get(key);

        if (existing != null && existing.isAfter(now)) {
            return false;   // 已被占用
        }
        locks.put(key, ttl == null ? Instant.MAX : now.plus(ttl));
        return true;
    }

    @Override
    public Optional<String> findResult(String key) {
        StoredResult stored = results.get(key);
        if (stored == null || stored.isExpired()) {
            return Optional.empty();
        }
        return Optional.of(stored.value());
    }

    @Override
    public void saveResult(String key, String result, Duration ttl) {
        results.put(key, new StoredResult(result,
                Instant.now().plus(ttl == null ? Duration.ofHours(24) : ttl)));
    }

    @Override
    public void release(String key) {
        locks.remove(key);
    }

    private record StoredResult(String value, Instant expiresAt) {
        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }
}
