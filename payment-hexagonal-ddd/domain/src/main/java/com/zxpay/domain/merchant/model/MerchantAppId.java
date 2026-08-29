package com.zxpay.domain.merchant.model;

import com.zxpay.sharedkernel.id.TypedId;

/**
 * 应用标识：商户下的一个接入应用。
 *
 * <p>为什么商户下面还要分应用？现实中同一个商户主体往往有多个独立系统：
 * 官网、App、小程序、线下门店，各自需要独立的密钥、回调地址与对账维度。
 * 如果全部共用一个凭证，密钥泄露的影响面就是全公司，
 * 且无法区分「哪条业务线在支付」。
 *
 * <p><b>业务幂等的唯一索引是 (app_id, merchant_order_no)，不是 merchant_order_no 单字段。</b>
 * 不同应用完全可能都用 "ORDER_001" 这种编号，只按单号做唯一会直接串单。
 */
public final class MerchantAppId extends TypedId {

    private static final String PREFIX = "APP";

    public MerchantAppId(String value) {
        super(value);
    }

    public static MerchantAppId generate() {
        return new MerchantAppId(generate(PREFIX));
    }

    public static MerchantAppId of(String value) {
        return new MerchantAppId(value);
    }
}
