package com.demo.payment.infra.idempotency;

import com.demo.payment.application.idempotency.IdempotencyRecord;
import com.demo.payment.application.idempotency.IdempotencyStore;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 幂等存储的内存实现。
 *
 * <p><b>生产环境必须换成 Redis：</b>
 * <pre>
 *   SETNX idempotency:{key} {fingerprint|PROCESSING} EX 86400
 * </pre>
 *
 * <p>关键点：
 * <ul>
 *   <li><b>必须用 SETNX（原子抢占）</b>，不能"先 GET 再 SET"——
 *       后者在并发下两个请求都会看到"不存在"，然后都执行业务逻辑。</li>
 *   <li><b>必须带过期时间</b>，否则键永久堆积。</li>
 *   <li>若用 DB 实现，则用唯一索引 + 捕获 DuplicateKeyException 达到同样效果。</li>
 * </ul>
 */
@org.springframework.stereotype.Repository
public class InMemoryIdempotencyStore implements IdempotencyStore {

    private final Map<String, IdempotencyRecord> store = new ConcurrentHashMap<>();

    @Override
    public Optional<IdempotencyRecord> tryAcquire(String key, String fingerprint, Duration ttl) {
        // computeIfAbsent 是原子的，等价于 Redis SETNX
        IdempotencyRecord existing = store.computeIfAbsent(key,
                k -> new IdempotencyRecord(k, fingerprint,
                        IdempotencyRecord.IdempotencyStatus.PROCESSING,
                        null, Instant.now(), Instant.now().plus(ttl)));

        // 已存在（非本次创建）→ 返回已有记录，表示重复请求
        if (!fingerprint.equals(existing.requestFingerprint()) || existing.status() != IdempotencyRecord.IdempotencyStatus.PROCESSING) {
            return Optional.of(existing);
        }
        // 本次刚创建的，检查是否为首次
        return existing.createdAt().equals(Instant.now()) ? Optional.empty() : Optional.of(existing);
    }

    @Override
    public void complete(String key, String resultSnapshot) {
        IdempotencyRecord rec = store.get(key);
        if (rec != null) {
            store.put(key, new IdempotencyRecord(key, rec.requestFingerprint(),
                    IdempotencyRecord.IdempotencyStatus.COMPLETED, resultSnapshot,
                    rec.createdAt(), rec.expireAt()));
        }
    }

    @Override
    public void fail(String key) {
        IdempotencyRecord rec = store.get(key);
        if (rec != null) {
            store.put(key, new IdempotencyRecord(key, rec.requestFingerprint(),
                    IdempotencyRecord.IdempotencyStatus.FAILED, null,
                    rec.createdAt(), rec.expireAt()));
        }
    }

    @Override
    public Optional<IdempotencyRecord> get(String key) {
        return Optional.ofNullable(store.get(key));
    }

    @Override
    public void release(String key) {
        store.remove(key);
    }
}
