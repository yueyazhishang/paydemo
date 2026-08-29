package com.demo.payment.domain.channel.spi;

import com.demo.payment.domain.channel.model.PaymentMethodType;
import com.demo.payment.shared.money.Currency;
import com.demo.payment.shared.money.Money;

/**
 * 路由上下文 —— 智能路由的输入。
 *
 * <p>路由不只看"支付方式"，还要综合金额、币种、地区、商户、终端场景。
 * 例如同样是用 Apple Pay：
 * <ul>
 *   <li>100 元小额 → 走 Stripe（费率低、接入简单）</li>
 *   <li>50000 元大额 → 走 Worldpay（大额成功率高、有 3DS 豁免）</li>
 *   <li>东南亚用户 → 走 Antom（本地收单，成功率高且费率低）</li>
 * </ul>
 */
public record RoutingContext(
        String merchantId,
        PaymentMethodType paymentMethod,
        Money amount,
        Currency currency,
        String countryCode,
        String clientIp,
        /** 终端场景：APP / WEB / H5 / QR / MINI_PROGRAM */
        String scene
) {}
