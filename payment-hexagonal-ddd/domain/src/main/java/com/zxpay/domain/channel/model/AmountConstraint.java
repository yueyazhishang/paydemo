package com.zxpay.domain.channel.model;

import com.zxpay.sharedkernel.money.Currency;
import com.zxpay.sharedkernel.money.Money;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 金额与币种约束。
 *
 * <p>路由期就要校验，而不是等到通道返回「金额超限」——后者意味着用户已经走到
 * 收银台才被拒，转化率直接归零。
 *
 * <p>限额必须<b>按币种分别配置</b>：不能拿人民币的 5 万单笔上限去卡日元的 5 万，
 * 那是两个相差两个数量级的数。这也是 {@link Money} 拒绝跨币种运算的同一条原则的延伸。
 */
public record AmountConstraint(
        Set<Currency> supportedCurrencies,

        /** 每个币种的上下限。未配置的币种视为不支持。 */
        Map<Currency, MoneyRange> perCurrencyLimit
) {

    public record MoneyRange(Money min, Money max) {
        public MoneyRange {
            if (min == null || max == null) {
                throw new IllegalArgumentException("min and max must not be null");
            }
            if (min.currency() != max.currency()) {
                throw new IllegalArgumentException("min and max must share the same currency");
            }
            if (min.isGreaterThan(max)) {
                throw new IllegalArgumentException("min must not exceed max");
            }
        }

        public boolean contains(Money amount) {
            return amount.isGreaterThanOrEqual(min) && !amount.isGreaterThan(max);
        }
    }

    public AmountConstraint {
        supportedCurrencies = supportedCurrencies == null
                ? Set.of()
                : Collections.unmodifiableSet(supportedCurrencies);
        perCurrencyLimit = perCurrencyLimit == null
                ? Map.of()
                : Collections.unmodifiableMap(perCurrencyLimit);
    }

    public boolean supportsCurrency(Currency currency) {
        return supportedCurrencies.contains(currency);
    }

    /**
     * 校验金额是否满足约束。
     *
     * @return 空表示通过；非空为不可用的原因，直接可用于路由排除日志
     */
    public Optional<String> validate(Money amount) {
        if (!supportsCurrency(amount.currency())) {
            return Optional.of("currency not supported: " + amount.currency().code());
        }
        MoneyRange range = perCurrencyLimit.get(amount.currency());
        if (range == null) {
            // 支持该币种但没配限额，视为不设限
            return Optional.empty();
        }
        if (!range.contains(amount)) {
            return Optional.of("amount out of range [" + range.min() + " ~ " + range.max() + "]: " + amount);
        }
        return Optional.empty();
    }
}
