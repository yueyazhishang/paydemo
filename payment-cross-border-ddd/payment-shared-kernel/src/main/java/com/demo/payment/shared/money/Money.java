package com.demo.payment.shared.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * 金额值对象。不可变，币种感知，以<b>最小货币单位</b>存储。
 *
 * <h3>为什么内部存 long 而不是 BigDecimal？</h3>
 * <ol>
 *   <li><b>浮点/精度事故</b>：{@code new BigDecimal("0.1")} 本身没问题，但
 *       {@code new BigDecimal(0.1)} 是 0.1000000000000000055511151231257827。
 *       只要有一次从 double 构造，整条链路就脏了。long 从物理上杜绝了这个入口。</li>
 *   <li><b>通道对齐</b>：微信、支付宝、Stripe、PayPal 的报文<b>全部</b>以最小单位传值
 *       （微信 v3 {@code amount.total} 单位是分，Stripe 是 cents）。用 long 存储可零成本对齐。</li>
 *   <li><b>DB 对齐</b>：{@code BIGINT} 比 {@code DECIMAL} 索引更紧凑、比较更快、跨库迁移无痛。</li>
 * </ol>
 *
 * <h3>它替你挡掉的三个真实事故</h3>
 * <ul>
 *   <li><b>KWD 三位小数</b>：1.234 KWD 在库里是 1234，不是 123。用 BigDecimal 存"元"的系统
 *       往往在 KWD 上多除一次 10，直接造成 10 倍资金差错。</li>
 *   <li><b>JPY 零小数位</b>：100 日元的最小单位就是 100。若按"乘以 100"的通用逻辑处理，
 *       会变成 10000，对账时表现为 100 倍长款。</li>
 *   <li><b>跨币种相加</b>：{@code plus()} 强制校验币种一致，避免 100 JPY + 1 USD = 101 的荒谬结果。</li>
 * </ul>
 *
 * <p><b>注意 {@code allocate()}：</b>分账、多次部分退款的余数分配必须使用该方法，
 * 谁自己写 {@code total * ratio / sum} 谁就会产生 1 分的差额，日终对账永远差几分钱。
 */
public final class Money implements Comparable<Money> {

    public static final Money ZERO_CNY = Money.ofMinor(0L, Currency.CNY);

    private final long minorUnits;
    private final Currency currency;

    private Money(long minorUnits, Currency currency) {
        this.minorUnits = minorUnits;
        this.currency = Objects.requireNonNull(currency, "currency");
    }

    // ---------- 工厂方法 ----------

    /** 从最小单位构造（推荐：报文解析、DB 读出的场景） */
    public static Money ofMinor(long minorUnits, Currency currency) {
        return new Money(minorUnits, currency);
    }

    /**
     * 从标准单位构造（"元"、"美元"），内部按币种指数换算为最小单位。
     *
     * @throws IllegalArgumentException 若小数位数超过该币种允许的精度（例如给 JPY 传 1.5）
     */
    public static Money ofMajor(BigDecimal major, Currency currency) {
        Objects.requireNonNull(major, "major");
        BigDecimal scaled = major.setScale(currency.exponent(), RoundingMode.UNNECESSARY);
        long minor = scaled.movePointRight(currency.exponent()).longValueExact();
        return new Money(minor, currency);
    }

    public static Money ofMajor(String major, Currency currency) {
        return ofMajor(new BigDecimal(major), currency);
    }

    // ---------- 取值 ----------

    public long minorUnits() { return minorUnits; }
    public Currency currency() { return currency; }

    /** 标准单位金额（元/美元），用于展示与对账报表 */
    public BigDecimal majorValue() {
        return BigDecimal.valueOf(minorUnits, currency.exponent());
    }

    public boolean isZero() { return minorUnits == 0L; }
    public boolean isPositive() { return minorUnits > 0L; }

    // ---------- 运算（全部要求同币种） ----------

    public Money plus(Money other) {
        assertSameCurrency(other);
        return new Money(Math.addExact(this.minorUnits, other.minorUnits), currency);
    }

    public Money minus(Money other) {
        assertSameCurrency(other);
        return new Money(Math.subtractExact(this.minorUnits, other.minorUnits), currency);
    }

    /** 按比例放大，用于手续费计算；{@code RoundingMode.HALF_UP} 是财务口径，显式声明不留暗坑 */
    public Money multiply(BigDecimal factor) {
        Objects.requireNonNull(factor, "factor");
        BigDecimal result = BigDecimal.valueOf(minorUnits).multiply(factor)
                .setScale(0, RoundingMode.HALF_UP);
        return new Money(result.longValueExact(), currency);
    }

    /**
     * 按权重拆分，余数逐个摊到前几份，保证 <b>sum(parts) == this</b> 严格成立。
     *
     * <p>例：100 分按 [1,1,1] 拆 → [34, 33, 33]，而不是 [33,33,33]（丢了 1 分）。
     * 分账、多次退款、优惠券分摊都必须走这里。
     */
    public Money[] allocate(int... weights) {
        if (weights == null || weights.length == 0) {
            throw new IllegalArgumentException("weights must not be empty");
        }
        long totalWeight = 0L;
        for (int w : weights) {
            if (w <= 0) {
                throw new IllegalArgumentException("weight must be positive: " + w);
            }
            totalWeight += w;
        }
        long remainder = minorUnits;
        Money[] result = new Money[weights.length];
        for (int i = 0; i < weights.length; i++) {
            long share = minorUnits * weights[i] / totalWeight;
            result[i] = new Money(share, currency);
            remainder -= share;
        }
        // 余数从第一份开始，每份补 1 个最小单位，直到分完
        for (int i = 0; remainder > 0 && i < result.length; i++, remainder--) {
            result[i] = new Money(result[i].minorUnits + 1, currency);
        }
        return result;
    }

    private void assertSameCurrency(Money other) {
        if (!this.currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "Currency mismatch: " + this.currency.code() + " vs " + other.currency.code());
        }
    }

    // ---------- 相等性与比较 ----------

    @Override
    public int compareTo(Money other) {
        assertSameCurrency(other);
        return Long.compare(this.minorUnits, other.minorUnits);
    }

    public boolean isGreaterThan(Money other) { return compareTo(other) > 0; }
    public boolean isLessThan(Money other) { return compareTo(other) < 0; }
    public boolean isGreaterThanOrEqual(Money other) { return compareTo(other) >= 0; }

    @Override
    public boolean equals(Object o) {
        if (this == o) { return true; }
        if (!(o instanceof Money money)) { return false; }
        return minorUnits == money.minorUnits && currency == money.currency;
    }

    @Override
    public int hashCode() { return Objects.hash(minorUnits, currency); }

    /** 输出形如 "CNY 12.34"，日志里一眼能看清币种，避免裸数字引发的误判 */
    @Override
    public String toString() {
        return currency.code() + " " + majorValue().toPlainString();
    }
}
