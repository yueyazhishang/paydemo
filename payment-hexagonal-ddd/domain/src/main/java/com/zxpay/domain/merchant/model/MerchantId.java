package com.zxpay.domain.merchant.model;

import com.zxpay.sharedkernel.id.TypedId;

/**
 * 商户标识。一个商户主体（一家公司/一个商家）对应一个。
 */
public final class MerchantId extends TypedId {

    private static final String PREFIX = "MCH";

    public MerchantId(String value) {
        super(value);
    }

    public static MerchantId generate() {
        return new MerchantId(generate(PREFIX));
    }

    public static MerchantId of(String value) {
        return new MerchantId(value);
    }
}
