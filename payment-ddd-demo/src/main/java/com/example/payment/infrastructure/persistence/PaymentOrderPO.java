package com.example.payment.infrastructure.persistence;

import com.example.payment.domain.shared.Channel;
import com.example.payment.domain.shared.Currency;
import com.example.payment.domain.payment.model.PaymentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 支付单持久化对象（PO）。
 * 领域聚合与 PO 分离：领域模型不携带 JPA 注解，由 Converter 相互转换，
 * 保证领域层纯净（不依赖 jakarta.persistence）。
 */
@Getter
@Setter
@Entity
@Table(name = "payment_order")
public class PaymentOrderPO {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false, unique = true, length = 64)
    private String paymentId;

    @Column(name = "biz_order_no", nullable = false, length = 64)
    private String bizOrderNo;

    @Column(name = "merchant_id", nullable = false, length = 64)
    private String merchantId;

    /** 最小货币单位金额 */
    @Column(name = "amount", nullable = false)
    private Long amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency", nullable = false, length = 8)
    private Currency currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 32)
    private Channel channel;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private PaymentStatus status;

    @Column(name = "channel_trade_no", length = 64)
    private String channelTradeNo;

    @Column(name = "pay_type", length = 32)
    private String payType;

    @Column(name = "pay_params", columnDefinition = "TEXT")
    private String payParams;

    @Column(name = "fail_reason")
    private String failReason;

    @Column(name = "merchant_notify_url", length = 512)
    private String merchantNotifyUrl;

    @Column(name = "expire_time")
    private Instant expireTime;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
