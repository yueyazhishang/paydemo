package com.demo.payment.domain.settlement.model;

import com.demo.payment.shared.money.Money;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 结算单 —— 结算限界上下文的聚合根（轻量建模）。
 *
 * <p><b>为什么结算要独立成限界上下文？</b>
 * 收单关心"这笔钱能不能收到"，结算关心"收到的钱什么时候、以什么比例给到商户"。
 * 两者的业务节奏完全不同：收单是秒级，结算是 T+1 日终批量。
 * 若混在一起，日终批处理会拖垮在线交易链路。
 *
 * <p>上下文之间通过领域事件协作：收单上下文发出 {@code PaymentSucceeded}，
 * 结算上下文订阅后生成结算明细。这样结算逻辑变更不影响支付主链路。
 */
public class SettlementOrder {

    private final String settlementNo;
    private final String merchantId;
    private final LocalDate settlementDate;
    private final Instant createdAt;

    /** 结算总额（订单金额之和） */
    private Money grossAmount;
    /** 通道手续费 */
    private Money feeAmount;
    /** 实际结算金额 = 总额 - 手续费 - 分账支出 */
    private Money netAmount;
    private SettlementStatus status;

    public SettlementOrder(String settlementNo, String merchantId, LocalDate settlementDate) {
        this.settlementNo = settlementNo;
        this.merchantId = merchantId;
        this.settlementDate = settlementDate;
        this.createdAt = Instant.now();
        this.status = SettlementStatus.PENDING;
    }

    /**
     * 计算净结算额。
     *
     * <p><b>必须用 {@code allocate} 之外的显式减法</b>：
     * 分账是"按比例拆分"，结算是"总额减去各项扣除"，
     * 两者语义不同 —— 分账要求 sum(parts) == total，
     * 结算则允许净额为负（倒挂，需人工处理）。
     */
    public void calculate(Money gross, Money fee) {
        this.grossAmount = gross;
        this.feeAmount = fee;
        this.netAmount = gross.minus(fee);
    }

    public String settlementNo() { return settlementNo; }
    public String merchantId() { return merchantId; }
    public LocalDate settlementDate() { return settlementDate; }
    public Money grossAmount() { return grossAmount; }
    public Money feeAmount() { return feeAmount; }
    public Money netAmount() { return netAmount; }
    public SettlementStatus status() { return status; }
    public Instant createdAt() { return createdAt; }

    public enum SettlementStatus {
        PENDING, CALCULATED, CONFIRMED, PAID, FAILED
    }
}
