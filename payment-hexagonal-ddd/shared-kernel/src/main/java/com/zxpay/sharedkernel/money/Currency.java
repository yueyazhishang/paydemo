package com.zxpay.sharedkernel.money;

import java.util.Locale;

/**
 * 币种。
 *
 * <p>支付领域里币种最容易被当成一个简单枚举，只记一个 code。真正会咬人的是
 * <b>小数位（minor unit）</b>：绝大多数币种是 2 位，但 JPY / KRW / VND 是 0 位，
 * BHD / KWD 是 3 位。如果统一按 2 位处理，日元就会被放大 100 倍——这是跨境支付
 * 最经典的线上事故之一。
 *
 * <p>因此币种必须自带 {@code minorUnits}，并且所有金额的最小单位换算都必须
 * 经过 {@link Money#minorUnits()} / {@link Money#ofMinor(long, Currency)}，
 * 严禁在业务代码里写死 {@code amount * 100}。
 */
public enum Currency {

    CNY("CNY", 2, "人民币"),
    USD("USD", 2, "美元"),
    EUR("EUR", 2, "欧元"),
    GBP("GBP", 2, "英镑"),
    HKD("HKD", 2, "港币"),
    TWD("TWD", 2, "新台币"),
    SGD("SGD", 2, "新加坡元"),
    AUD("AUD", 2, "澳元"),
    CAD("CAD", 2, "加元"),
    CHF("CHF", 2, "瑞士法郎"),
    THB("THB", 2, "泰铢"),
    MYR("MYR", 2, "马来西亚林吉特"),
    IDR("IDR", 2, "印尼盾"),
    PHP("PHP", 2, "菲律宾比索"),
    INR("INR", 2, "印度卢比"),
    BRL("BRL", 2, "巴西雷亚尔"),
    MXN("MXN", 2, "墨西哥比索"),
    AED("AED", 2, "阿联酋迪拉姆"),
    SAR("SAR", 2, "沙特里亚尔"),
    RUB("RUB", 2, "俄罗斯卢布"),
    TRY("TRY", 2, "土耳其里拉"),
    ZAR("ZAR", 2, "南非兰特"),
    SEK("SEK", 2, "瑞典克朗"),
    NOK("NOK", 2, "挪威克朗"),
    DKK("DKK", 2, "丹麦克朗"),
    PLN("PLN", 2, "波兰兹罗提"),
    NZD("NZD", 2, "新西兰元"),

    /** 零小数位币种：最小单位就是 1 元，不可再除 100。 */
    JPY("JPY", 0, "日元"),
    KRW("KRW", 0, "韩元"),
    VND("VND", 0, "越南盾"),
    CLP("CLP", 0, "智利比索"),

    /** 三位小数币种。 */
    BHD("BHD", 3, "巴林第纳尔"),
    KWD("KWD", 3, "科威特第纳尔"),
    ;

    private final String code;
    private final int minorUnits;
    private final String displayName;

    Currency(String code, int minorUnits, String displayName) {
        this.code = code;
        this.minorUnits = minorUnits;
        this.displayName = displayName;
    }

    public String code() {
        return code;
    }

    /** 小数位数：CNY=2，JPY=0，KWD=3。 */
    public int minorUnits() {
        return minorUnits;
    }

    public String displayName() {
        return displayName;
    }

    public static Currency of(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("currency code must not be blank");
        }
        return Currency.valueOf(code.trim().toUpperCase(Locale.ROOT));
    }

    public static boolean isKnown(String code) {
        try {
            of(code);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
