package com.zxpay.domain.merchant.model;

/**
 * 商户状态。
 *
 * <p>注意 {@link #SUSPENDED} 与 {@link #CLOSED} 的区别：
 * 暂停通常是风控或欠费导致，恢复后业务照常；关闭是商户主动注销，不可恢复。
 * 两者对「在途交易」的处理也不同——暂停时已创建的支付单应允许继续完成
 * （用户钱都付了不可能不退），关闭则需要人工介入处理未完结资金。
 */
public enum MerchantStatus {

    /** 正常。 */
    ACTIVE("正常"),

    /** 暂停受理新交易。已创建的交易仍可完成。 */
    SUSPENDED("已暂停"),

    /** 已注销。不可恢复。 */
    CLOSED("已关闭"),
    ;

    private final String displayName;

    MerchantStatus(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /** 是否允许发起新交易。 */
    public boolean canAcceptNewPayment() {
        return this == ACTIVE;
    }
}
