package com.demo.payment.domain.acquiring.service;

import com.demo.payment.domain.acquiring.model.aggregate.PaymentOrder;
import com.demo.payment.domain.channel.model.ChannelCapability;
import com.demo.payment.shared.money.Money;

/**
 * 退款策略领域服务。
 *
 * <p><b>什么时候该用领域服务，而不是把逻辑塞进聚合根？</b>
 * 判断标准：这段逻辑是否<b>只依赖聚合内部状态</b>。
 * 退款有效性校验需要同时看「订单状态 + 通道能力（是否支持部分退款、退款期限）」，
 * 后者不属于聚合，因此提成领域服务，由应用层把能力作为参数传入。
 * 这样领域服务依然保持纯净（无外部依赖），可零 mock 测试。
 */
public interface RefundPolicyService {

    /**
     * 校验本次退款是否被允许。
     *
     * @param order      支付单
     * @param capability 该订单所用通道的能力矩阵
     * @param amount     本次退款金额
     * @return 校验结果，含拒绝原因
     */
    RefundCheckResult check(PaymentOrder order, ChannelCapability capability, Money amount);
}
