package com.example.payment.domain.shared;

import lombok.Getter;

/**
 * 币种（ISO 4217）。scale 为最小货币单位相对主单位的换算位数，
 * 例如 CNY scale=2（1 元 = 100 分），JPY scale=0（最小单位即主单位）。
 * 领域内金额一律使用「最小货币单位」的 long 存储，见 {@link Money}。
 */
@Getter
public enum Currency {

    CNY("人民币", 2),
    HKD("港币", 2),
    USD("美元", 2),
    EUR("欧元", 2),
    GBP("英镑", 2),
    JPY("日元", 0);

    private final String displayName;
    private final int scale;

    Currency(String displayName, int scale) {
        this.displayName = displayName;
        this.scale = scale;
    }
}
