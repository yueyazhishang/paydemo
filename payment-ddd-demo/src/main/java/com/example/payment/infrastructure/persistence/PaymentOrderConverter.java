package com.example.payment.infrastructure.persistence;

import com.example.payment.domain.payment.model.PaymentOrder;
import com.example.payment.domain.shared.Channel;
import com.example.payment.domain.shared.Currency;
import com.example.payment.domain.shared.Money;
import com.example.payment.domain.payment.model.PaymentStatus;
import org.springframework.stereotype.Component;

/**
 * 领域聚合 ↔ 持久化对象 转换器。
 * 让领域模型彻底摆脱 JPA 注解依赖（领域纯净性）。
 */
@Component
public class PaymentOrderConverter {

    public PaymentOrderPO toPO(PaymentOrder order) {
        PaymentOrderPO po = new PaymentOrderPO();
        po.setPaymentId(order.getPaymentId());
        po.setBizOrderNo(order.getBizOrderNo());
        po.setMerchantId(order.getMerchantId());
        po.setAmount(order.getAmount().getAmountMinor());
        po.setCurrency(order.getAmount().getCurrency());
        po.setChannel(order.getChannel());
        po.setStatus(order.getStatus());
        po.setChannelTradeNo(order.getChannelTradeNo());
        po.setPayType(order.getPayType());
        po.setPayParams(order.getPayParams());
        po.setFailReason(order.getFailReason());
        po.setMerchantNotifyUrl(order.getMerchantNotifyUrl());
        po.setExpireTime(order.getExpireTime());
        return po;
    }

    public PaymentOrder toDomain(PaymentOrderPO po) {
        return PaymentOrder.rehydrate(
                po.getPaymentId(), po.getBizOrderNo(), po.getMerchantId(),
                Money.ofMinor(po.getAmount(), po.getCurrency()),
                po.getChannel(), po.getStatus(),
                po.getChannelTradeNo(), po.getPayType(), po.getPayParams(),
                po.getFailReason(), po.getMerchantNotifyUrl(), po.getExpireTime());
    }
}
