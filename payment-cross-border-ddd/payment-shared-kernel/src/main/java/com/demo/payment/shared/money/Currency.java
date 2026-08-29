package com.demo.payment.shared.money;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * 币种值对象。
 *
 * <p><b>为什么不用 {@link java.util.Currency}？</b>
 * 支付系统里币种不只是"三个字母"，它还绑定了三件 JDK 不提供的东西：
 * <ol>
 *   <li><b>最小单位指数（exponent）</b>：JPY=0、CNY=2、KWD=3。JDK 虽然也有
 *       {@code getDefaultFractionDigits()}，但它返回的是 ISO 4217 的默认值，
 *       而真实通道经常<b>偏离</b>该值（例如部分通道对 JPY 要求传 2 位小数补齐），
 *       需要能在枚举上直接看到并在适配层做换算。</li>
 *   <li><b>通道可用性</b>：CNY 在 Stripe 上可结算但微信不能收 USD，这是能力矩阵的一部分。</li>
 *   <li><b>舍入策略</b>：金额换算（汇率）时需要显式声明，不能隐式 HALF_UP。</li>
 * </ol>
 *
 * <p><b>设计决策：用枚举而不是开放值。</b>
 * 新手常把币种做成 {@code String}，于是 {@code "cny"} / {@code "CNY"} / {@code "人民币"}
 * 三份数据同时存在库里，对账时炸掉。枚举强制收敛到有限集；新增币种是<b>编译期事件</b>，
 * 会逼着你检查所有 switch 分支——这正是我们想要的痛感。
 */
public enum Currency {

    CNY("CNY", "人民币",   "¥",  2),
    USD("USD", "美元",     "$",  2),
    EUR("EUR", "欧元",     "€",  2),
    GBP("GBP", "英镑",     "£",  2),
    JPY("JPY", "日元",     "¥",  0),  // 零小数位：100 JPY 的最小单位就是 100，不是 10000
    KRW("KRW", "韩元",     "₩",  0),
    HKD("HKD", "港币",     "HK$", 2),
    SGD("SGD", "新加坡元", "S$", 2),
    AUD("AUD", "澳元",     "A$", 2),
    THB("THB", "泰铢",     "฿",  2),
    IDR("IDR", "印尼盾",   "Rp", 2),  // 虽 ISO 定义为 2，实际业务多按 0 处理，通道侧需确认
    KWD("KWD", "科威特第纳尔", "KD", 3), // 三位小数：唯一常见的高精度币种，是金额 bug 的高发区
    BRL("BRL", "巴西雷亚尔", "R$", 2),
    PHP("PHP", "菲律宾比索", "₱", 2),
    SAR("SAR", "沙特里亚尔", "SAR", 2),
    ;

    private final String code;
    private final String displayName;
    private final String symbol;
    /** 最小单位指数：10^exponent 个最小单位 = 1 个标准单位 */
    private final int exponent;

    Currency(String code, String displayName, String symbol, int exponent) {
        this.code = code;
        this.displayName = displayName;
        this.symbol = symbol;
        this.exponent = exponent;
    }

    public static Optional<Currency> parse(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(c -> c.code.equals(normalized))
                .findFirst();
    }

    /** 严格解析，找不到就抛异常 —— 币种错误必须快速失败，绝不能静默降级 */
    public static Currency require(String code) {
        return parse(code).orElseThrow(() ->
                new IllegalArgumentException("Unsupported currency code: " + code));
    }

    /** 1 个标准单位 = 10^exponent 个最小单位 */
    public long minorUnitsPerMajor() {
        return switch (exponent) {
            case 0 -> 1L;
            case 2 -> 100L;
            case 3 -> 1000L;
            default -> (long) Math.pow(10, exponent);
        };
    }

    /** 该币种是否为"零小数位"币种（日元、韩元），通道报文拼接时最容易出错的地方 */
    public boolean isZeroDecimal() {
        return exponent == 0;
    }

    public String code() { return code; }
    public String displayName() { return displayName; }
    public String symbol() { return symbol; }
    public int exponent() { return exponent; }
}
