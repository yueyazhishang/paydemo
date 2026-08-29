package com.demo.payment.domain.channel.spi;

import com.demo.payment.domain.acquiring.model.vo.OutTradeNo;
import com.demo.payment.domain.channel.model.PaymentMethodType;
import com.demo.payment.shared.money.Money;

import java.util.HashMap;
import java.util.Map;

/**
 * 发起支付的命令对象。
 *
 * <p><b>关于 {@code extraParams}：</b>
 * 这是"统一抽象"与"通道特殊性"之间妥协的产物。
 * 理想情况是所有参数都进强类型字段，但现实是：
 * 微信 JSAPI 必须要 openid，Stripe 必须要 payment_method，Antom 的 APM 各有各的必填项。
 * 若把这些都提升为统一字段，接口会迅速腐化成"所有通道参数的并集"，
 * 每个通道只用其中 3 个，其余 20 个都是噪音。
 *
 * <p>因此保留一个逃生舱 {@code extraParams}，但<b>严格约束其使用</b>：
 * 只允许放通道特有的非核心参数，核心业务字段（金额、订单号、币种）必须在强类型字段上。
 */
public record PayCommand(
        OutTradeNo outTradeNo,
        Money amount,
        PaymentMethodType paymentMethod,
        String subject,
        String notifyUrl,
        String returnUrl,
        String clientIp,

        /**
         * 付款人在通道侧的身份标识。
         * 微信 JSAPI → openid；Stripe → customer_id；支付宝 → buyer_id
         */
        String payerId,

        /**
         * 支付凭证（支付方式为凭证网络类时使用）。
         * Apple Pay → PKPaymentToken 的 paymentData；Stripe → payment_method_id
         */
        String paymentCredential,

        /** 幂等键，由上层按通道能力决定如何传递 */
        String idempotencyKey,

        /** 订单过期时间（秒），部分通道支持（微信 time_expire、支付宝 timeout_express） */
        Integer expireSeconds,

        /** 国家或地区码（ISO 3166-1 alpha-2），海外通道必填 */
        String countryCode,

        /** 语言（Antom、PayPal 的收银台本地化） */
        String locale,

        /** 通道特有参数 */
        Map<String, String> extraParams
) {
    public PayCommand {
        if (extraParams == null) {
            extraParams = new HashMap<>();
        }
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private OutTradeNo outTradeNo;
        private Money amount;
        private PaymentMethodType paymentMethod;
        private String subject;
        private String notifyUrl;
        private String returnUrl;
        private String clientIp;
        private String payerId;
        private String paymentCredential;
        private String idempotencyKey;
        private Integer expireSeconds;
        private String countryCode;
        private String locale;
        private final Map<String, String> extraParams = new HashMap<>();

        public Builder outTradeNo(OutTradeNo v) { this.outTradeNo = v; return this; }
        public Builder amount(Money v) { this.amount = v; return this; }
        public Builder paymentMethod(PaymentMethodType v) { this.paymentMethod = v; return this; }
        public Builder subject(String v) { this.subject = v; return this; }
        public Builder notifyUrl(String v) { this.notifyUrl = v; return this; }
        public Builder returnUrl(String v) { this.returnUrl = v; return this; }
        public Builder clientIp(String v) { this.clientIp = v; return this; }
        public Builder payerId(String v) { this.payerId = v; return this; }
        public Builder paymentCredential(String v) { this.paymentCredential = v; return this; }
        public Builder idempotencyKey(String v) { this.idempotencyKey = v; return this; }
        public Builder expireSeconds(Integer v) { this.expireSeconds = v; return this; }
        public Builder countryCode(String v) { this.countryCode = v; return this; }
        public Builder locale(String v) { this.locale = v; return this; }
        public Builder extra(String k, String v) { this.extraParams.put(k, v); return this; }

        public PayCommand build() {
            return new PayCommand(outTradeNo, amount, paymentMethod, subject, notifyUrl,
                    returnUrl, clientIp, payerId, paymentCredential, idempotencyKey,
                    expireSeconds, countryCode, locale, extraParams);
        }
    }
}
