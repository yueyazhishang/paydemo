package com.demo.payment.domain.settlement.model;

import com.demo.payment.shared.money.Money;

/**
 * 分账指令。
 *
 * <p>典型场景：平台型电商，一笔 100 元订单要分给平台 10 元、商家 85 元、推广方 5 元。
 *
 * <p><b>核心难点是金额分配的余数问题：</b>
 * 100 元按 10:85:5 分，若各自独立计算再四舍五入，可能出现 10+85+5=100
 * 或 99 或 101 的三种结果。必须用 {@link Money#allocate(int...)} 保证
 * <b>各部分之和严格等于原额</b>，否则日终对账必然出现分差。
 */
public record SplitInstruction(
        String instructionNo,
        String payerMerchantId,
        /** 收款方 ID（商户/个人） */
        String payeeId,
        /** 分账金额（由 allocate 计算得出，保证无余数丢失） */
        Money amount,
        SplitType type,
        String description
) {
    public enum SplitType {
        /** 按比例 */
        RATIO,
        /** 固定金额 */
        FIXED,
        /** 平台抽成 */
        PLATFORM_FEE
    }
}
