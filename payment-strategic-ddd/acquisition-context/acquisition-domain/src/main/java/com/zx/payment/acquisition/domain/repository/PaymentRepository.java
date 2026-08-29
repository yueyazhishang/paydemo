package com.zx.payment.acquisition.domain.repository;

import com.zx.payment.acquisition.domain.model.Payment;
import com.zx.payment.acquisition.domain.model.PaymentStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 仓储端口（定义在领域层，实现在基础设施层——依赖倒置）。
 *
 * 仓储的语义是【模拟内存集合】：应用层拿到 Payment 后像操作内存对象一样修改，
 * 然后 save() 回去，仓储负责把变更同步到存储。领域层不知道底层是 MySQL 还是 Redis。
 *
 * 关于 save() 为什么不拆成 add/update：
 *   Evans 的原意是区分"新增"和"更新"，但实践中聚合有 ID、仓储可自行判断，
 *   统一用 save() 是业界主流简化（Spring Data 也这么做）。
 *   真正的重点不是方法名，而是【save 必须带乐观锁】——见 save 的注释。
 */
public interface PaymentRepository {

    Optional<Payment> findById(String paymentId);

    /** 幂等支撑：按商户订单号查询。重复下单时命中即返回，不产生第二笔支付单。 */
    Optional<Payment> findByMerchantOrderNo(String merchantId, String merchantOrderNo);

    /**
     * 持久化聚合。
     *
     * 必须以乐观锁方式实现：
     *   UPDATE t_payment SET ..., version = version + 1
     *   WHERE payment_id = ? AND version = ?
     * 影响行数为 0 表示版本已变，抛 OptimisticConcurrencyException。
     *
     * 没有这层保护，并发的状态推进会互相覆盖（见 Payment 类注释里的 ABA 例子）。
     */
    void save(Payment payment);

    /**
     * 扫描待关单的支付单：未到终态且已过期。
     * 超时关单定时任务使用。limit 防一次捞出过多。
     */
    List<Payment> findExpiredCandidates(Instant now, List<PaymentStatus> statuses, int limit);

    /** 下一跳标识（分布式部署时用于生成 paymentId，demo 用 UUID 即可）。 */
    default String nextId() {
        return java.util.UUID.randomUUID().toString().replace("-", "");
    }
}
