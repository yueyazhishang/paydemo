package com.zx.payment.shared;

import java.util.Locale;

/**
 * 值对象：币种。
 *
 * scale 是各币种的最小单位位数——这是很多系统踩坑的地方：
 * CNY/USD 是 2 位，但 JPY 是 0 位（日元没有小数），KWD/BHD 是 3 位。
 * 少数字系统直接用 2 硬编码，接入日元就会把 100 日元记成 1 日元。
 */
public enum CurrencyCode {

    CNY("CNY", 2),
    USD("USD", 2),
    EUR("EUR", 2),
    HKD("HKD", 2),
    GBP("GBP", 2),
    JPY("JPY", 0),
    KRW("KRW", 0),
    KWD("KWD", 3),
    SGD("SGD", 2),
    AUD("AUD", 2);

    private final String code;
    private final int scale;

    CurrencyCode(String code, int scale) {
        this.code = code;
        this.scale = scale;
    }

    public String code() {
        return code;
    }

    /** 最小单位位数。JPY=0，KWD=3，多数币种=2。 */
    public int scale() {
        return scale;
    }

    public static CurrencyCode of(String code) {
        String normalized = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        for (CurrencyCode c : values()) {
            if (c.code.equals(normalized) || c.name().equals(normalized)) {
                return c;
            }
        }
        throw new IllegalArgumentException("不支持的币种: " + code);
    }
}
