package com.demo.payment.application.idempotency;

import java.time.Duration;
import java.util.Optional;

/**
 * 幂等存储端口（应用层定义，基础设施层实现）。
 *
 * <p>实现要点：
 * <ul>
 *   <li>必须用 <b>Redis SETNX</b> 或 DB 唯一索引做<b>原子抢占</b>，
 *       不能用"先查再写"两步 —— 那样并发下两个请求都会查到"不存在"，
 *       然后都去执行业务逻辑，幂等形同虚设。</li>
 *   <li>必须设置过期时间，避免键永久堆积。有效期应覆盖业务最长处理时间
 *       （支付业务通常 24 小时）。</li>
 *   <li>处理中的请求被重复调用时，应返回"处理中"而非再次执行。</li>
 * </ul>
 */
public interface IdempotencyStore {

    /**
     * 原子抢占幂等键。
     *
     * @return 抢占成功返回 empty；抢占失败返回已有记录（表示重复请求）
     */
    Optional<IdempotencyRecord> tryAcquire(String key, String fingerprint, Duration ttl);

    /** 标记处理完成并写入结果快照 */
    void complete(String key, String resultSnapshot);

    /** 标记失败，允许后续重试 */
    void fail(String key);

    Optional<IdempotencyRecord> get(String key);

    void release(String key);
}
