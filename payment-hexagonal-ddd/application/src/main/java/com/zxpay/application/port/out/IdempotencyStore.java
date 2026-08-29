package com.zxpay.application.port.out;

import java.time.Duration;
import java.util.Optional;

/**
 * 出站端口：接口层幂等存储。
 *
 * <p>这是三层幂等中的第一层（另外两层是业务唯一索引与通道幂等键）：
 * 商户调用我们的下单接口时，若因网络抖动重试，
 * 我们要能识别出「这是同一个请求」，返回第一次的结果而不是再建一单。
 *
 * <p>分布式环境下必须用 Redis 这类共享存储，
 * 本地 {@code ConcurrentHashMap} 在多实例部署下不起作用——
 * 重试请求很可能落到另一台机器上。
 *
 * <p>实现上要注意<b>原子性</b>：判断是否存在和写入必须是原子的
 * （Redis 的 {@code SETNX} 或 Lua 脚本）。
 * 分成两步做，两个并发的同键请求会同时通过检查，幂等直接失效。
 */
public interface IdempotencyStore {

    /**
     * 尝试占用幂等键。
     *
     * @param key    幂等键，通常是 {@code appId + ":" + idempotencyKey}
     * @param ttl    占用时长。超过后允许重新处理（例如商户 24 小时后重试同一单号）
     * @return 占用成功返回 true（首次请求）；已被占用返回 false（重复请求）
     */
    boolean tryAcquire(String key, Duration ttl);

    /**
     * 读取已完成的幂等结果。
     *
     * <p>第一次请求处理完后，把响应快照存进来；
     * 重复请求直接取出返回，保证「同一请求多次调用，返回结果完全一致」。
     * 这点很重要：若第二次返回不同的订单号，商户侧照样会乱。
     *
     * @return 首次处理的结果快照；不存在返回空
     */
    Optional<String> findResult(String key);

    /** 保存结果快照。 */
    void saveResult(String key, String result, Duration ttl);

    /** 释放占用。仅在处理失败、允许重试时调用。 */
    void release(String key);
}
