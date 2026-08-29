package com.demo.payment.domain.acquiring.repository;

import com.demo.payment.domain.acquiring.model.aggregate.PaymentOrder;
import com.demo.payment.domain.acquiring.model.aggregate.PaymentOrderId;
import com.demo.payment.domain.acquiring.model.vo.OutTradeNo;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.Lock;

/**
 * 支付单仓储接口（domain 层定义，infrastructure 层实现）。
 *
 * <p><b>为什么仓储接口要放在 domain 层？</b>
 * 这是 DDD 的经典争议点。放 domain 层的理由是：仓储操作的语义
 * （"按商户订单号查找"、"保存聚合"）是领域概念，不是技术概念。
 * 接口放这里，领域层才能在不引入任何持久化框架的前提下表达持久化需求。
 * 实现（MyBatis / JPA / 内存 Map）放 infrastructure，运行时注入。
 *
 * <p><b>关于 {@code obtainLock}：</b>
 * 支付单是高并发写对象，回调、查证补偿、关单定时任务可能同时到达同一笔单。
 * 仓储必须提供获取分布式锁的能力，由应用层显式加锁。
 * 把锁藏在 save() 内部是错的 —— 那会让"读-改-写"的边界变得不可见。
 */
public interface PaymentOrderRepository {

    Optional<PaymentOrder> findById(PaymentOrderId id);

    /**
     * 按商户订单号 + 商户号查找。
     *
     * <p><b>必须带商户号</b>：只用 merchantOrderNo 查询存在跨商户数据泄露风险。
     * 唯一索引也必须是 (merchant_id, merchant_order_no) 联合唯一，
     * 因为不同商户完全可能使用相同的订单号（比如都叫 "ORDER001"）。
     */
    Optional<PaymentOrder> findByMerchantOrderNo(String merchantId, String merchantOrderNo);

    /** 按通道订单号反查（回调通知到达时使用） */
    Optional<PaymentOrder> findByOutTradeNo(OutTradeNo outTradeNo);

    /**
     * 保存聚合。
     *
     * <p><b>实现必须做乐观锁</b>：UPDATE ... WHERE id = ? AND version = ?，
     * 影响行数为 0 说明有并发写入，必须抛出并发异常让上层重试，
     * 绝不能无条件覆盖 —— 否则后到的错误结果会覆盖先到的正确结果。
     */
    void save(PaymentOrder order);

    /** 扫描处于处理中且已超时的订单，用于查证补偿与自动关单 */
    List<PaymentOrder> findTimeoutCandidates(int limitMinutes, int limit);

    /**
     * 获取该订单的分布式锁。
     *
     * @return 锁对象，调用方必须在 finally 中释放
     */
    Lock obtainLock(PaymentOrderId id);
}
