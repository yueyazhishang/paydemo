package com.demo.payment.domain.settlement.model;

import com.demo.payment.shared.money.Money;

import java.time.Instant;

/**
 * 提现单。
 *
 * <p><b>提现是资金出账，风险等级最高</b>，因此比支付更保守：
 * <ul>
 *   <li>必须校验可用余额（已结算 - 已提现 - 冻结中）</li>
 *   <li>大额提现需人工复核（超过阈值）</li>
 *   <li>必须做风控校验（洗钱、欺诈）</li>
 *   <li>通常是异步到账（银行通道 T+0/T+1）</li>
 * </ul>
 */
public class Withdrawal {

    private final String withdrawalNo;
    private final String merchantId;
    private final Money amount;
    private final String payeeAccount;
    private final Instant createdAt;

    private WithdrawalStatus status;
    private String channelTransactionId;
    private String failReason;
    private Instant finishedAt;

    public Withdrawal(String withdrawalNo, String merchantId, Money amount, String payeeAccount) {
        this.withdrawalNo = withdrawalNo;
        this.merchantId = merchantId;
        this.amount = amount;
        this.payeeAccount = payeeAccount;
        this.createdAt = Instant.now();
        this.status = WithdrawalStatus.INIT;
    }

    public void markProcessing(String channelTransactionId) {
        this.status = WithdrawalStatus.PROCESSING;
        this.channelTransactionId = channelTransactionId;
    }

    public void markSucceeded() {
        this.status = WithdrawalStatus.SUCCEEDED;
        this.finishedAt = Instant.now();
    }

    public void markFailed(String reason) {
        this.status = WithdrawalStatus.FAILED;
        this.failReason = reason;
        this.finishedAt = Instant.now();
    }

    /** 是否计入"已提现"余额占用 */
    public boolean occupiesBalance() {
        return status != WithdrawalStatus.FAILED;
    }

    public String withdrawalNo() { return withdrawalNo; }
    public String merchantId() { return merchantId; }
    public Money amount() { return amount; }
    public String payeeAccount() { return payeeAccount; }
    public Instant createdAt() { return createdAt; }
    public WithdrawalStatus status() { return status; }
    public String channelTransactionId() { return channelTransactionId; }
    public String failReason() { return failReason; }
    public Instant finishedAt() { return finishedAt; }

    public enum WithdrawalStatus {
        INIT, PROCESSING, SUCCEEDED, FAILED
    }
}
