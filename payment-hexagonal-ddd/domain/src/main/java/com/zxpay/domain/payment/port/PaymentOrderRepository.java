package com.zxpay.domain.payment.port;

import com.zxpay.domain.channel.model.ChannelCode;
import com.zxpay.domain.merchant.model.MerchantAppId;
import com.zxpay.domain.payment.model.PaymentOrder;
import com.zxpay.domain.payment.model.PaymentOrderId;
import com.zxpay.domain.payment.model.PaymentStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 出站端口：支付单仓储。
 *
 * <p>领域层定义「我需要什么样的持久化能力」，基础设施层决定用 MySQL 分库分表、
 * 还是别的实现。本 Demo 中是内存 Map（见 {@code InMemoryPaymentOrderRepository}）。
 *
 * <p>几个方法都不是凑数的，各自对应一条真实的业务链路：
 * <ul>
 *   <li>{@code findByMerchantOrderNo}：<b>业务幂等</b>。商户重试下单时，
 *       必须先查「这笔业务是不是已经有单了」，有则返回原单而不是新建。
 *       注意唯一索引是 {@code (app_id, merchant_order_no)} 而不是单字段——
 *       不同商户完全可以都用 "ORDER_1" 这种编号。</li>
 *   <li>{@code findByChannelTransactionId}：<b>回调定位</b>。
 *       通道回调时只给出通道交易号，必须能反查到我们的支付单。
 *       这也是为什么支付尝试要记录 channelTransactionId。</li>
 *   <li>{@code findPendingBefore}：<b>补偿扫描</b>。
 *       找出长时间停留在中间态的订单，推动主动查单。
 *       生产里这个方法走的是「状态 + 时间」的联合索引，且必须分页，
 *       否则一次全表扫描会打爆数据库。</li>
 * </ul>
 */
public interface PaymentOrderRepository {

    Optional<PaymentOrder> findById(PaymentOrderId id);

    Optional<PaymentOrder> findByMerchantOrderNo(MerchantAppId appId, String merchantOrderNo);

    Optional<PaymentOrder> findByChannelTransactionId(ChannelCode channel, String channelTransactionId);

    /**
     * 按下发给通道的订单号查找。
     *
     * <p>回调里最常见的定位依据。注意它与 {@code merchantOrderNo} 可能不同
     * （切换通道后带序号后缀），因此必须单独建索引，不能复用商户订单号查找。
     */
    Optional<PaymentOrder> findByChannelOrderNo(ChannelCode channel, String channelOrderNo);

    /**
     * 保存。实现必须使用乐观锁：{@code UPDATE ... WHERE id = ? AND version = ?}，
     * 影响行数为 0 时抛出 {@code ConcurrencyConflictException}。
     */
    void save(PaymentOrder order);

    /**
     * 扫描长时间停留在中间态的订单，供补偿任务主动查单。
     *
     * @param statuses 关注的中间状态
     * @param before   最后状态变更时间早于该时间点
     * @param limit    分页大小，必须限制，防止全表扫描
     */
    List<PaymentOrder> findPendingBefore(List<PaymentStatus> statuses, Instant before, int limit);
}
