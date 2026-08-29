package com.zxpay.domain.merchant.model;

import com.zxpay.domain.channel.model.ChannelCode;
import com.zxpay.domain.channel.model.PaymentMethod;

import java.util.Collections;
import java.util.Set;

/**
 * 商户与通道的签约关系（在通道侧开通的商户号配置）。
 *
 * <p>这是「商户」与「通道」两个上下文之间的<b>防腐层</b>：
 * 通道侧需要大量机构特有字段（微信的 mch_id / sub_mch_id、
 * 支付宝的 app_id / pid、Stripe 的 account_id），
 * 这些脏东西全部收拢在这里，不让它扩散到支付主流程里。
 *
 * <p>支付上下文只问一句「这笔交易该用哪个通道商户号」，
 * 不问「微信的子商户号叫 sub_mch_id 还是 sub_merchant_id」。
 *
 * <p>{@code feeRateBps} 用<b>基点</b>（万分之一）表示费率：
 * 千六 = 60 bps。整数存储，避免浮点误差在资金计算里累积。
 */
public record ChannelContract(
        ChannelCode channel,

        /** 通道侧商户号。如微信 mch_id、Stripe account_id。 */
        String channelMerchantId,

        /** 通道侧子商户号。服务商模式下使用，直连模式为空。 */
        String channelSubMerchantId,

        /** 费率，基点（万分之一）。千六 = 60。 */
        int feeRateBps,

        /** 该签约下允许使用的支付方式。 */
        Set<PaymentMethod> allowedMethods,

        boolean enabled
) {

    public ChannelContract {
        allowedMethods = allowedMethods == null ? Set.of() : Collections.unmodifiableSet(allowedMethods);
    }

    public static ChannelContract of(ChannelCode channel, String merchantId, int feeRateBps,
                                     Set<PaymentMethod> methods) {
        return new ChannelContract(channel, merchantId, null, feeRateBps, methods, true);
    }

    public boolean allows(PaymentMethod method) {
        return enabled && allowedMethods.contains(method);
    }

    /**
     * 服务商模式下，实际下单使用的商户号。
     *
     * <p>微信服务商模式下，接口传的是服务商 mch_id + 子商户 sub_mch_id；
     * 直连模式下只传 mch_id。这个差异收在这里，适配器直接取用。
     */
    public boolean isServiceProviderMode() {
        return channelSubMerchantId != null && !channelSubMerchantId.isBlank();
    }
}
