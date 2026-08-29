package com.example.payment.domain.service;

import com.example.payment.domain.gateway.BillRecord;
import com.example.payment.domain.payment.model.PaymentOrder;
import com.example.payment.domain.reconciliation.model.DiffType;
import com.example.payment.domain.reconciliation.model.ReconciliationItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 对账领域服务：账单核对规则（核心业务规则，非编排）。
 *
 * <p>双向核对规则：
 * <ul>
 *   <li>我方成功、渠道账单缺失 → LOCAL_MORE（少收款风险，重点告警）</li>
 *   <li>渠道成功、我方无对应成功单 → CHANNEL_MORE（疑似掉单，以渠道为准修复状态）</li>
 *   <li>双方都成功但金额不一致 → AMOUNT_MISMATCH（串单/篡改风险）</li>
 * </ul>
 * 规则与数据结构（匹配键为我方单号）均属于对账领域的通用语言，故收敛在领域层；
 * 应用层只负责准备两侧数据、调用本服务、持久化结果。
 */
public final class ReconciliationDomainService {

    private ReconciliationDomainService() {
    }

    /**
     * 执行核对。
     *
     * @param batchNo        批次号
     * @param localOrders    本地该渠道已成功的支付单
     * @param channelRecords 渠道账单记录（key：我方单号）
     * @return 差异明细列表（无差异返回空列表）
     */
    public static List<ReconciliationItem> reconcile(String batchNo,
                                                     List<PaymentOrder> localOrders,
                                                     Map<String, BillRecord> channelRecords) {
        List<ReconciliationItem> diffs = new ArrayList<>();

        Map<String, PaymentOrder> localMap = localOrders.stream()
                .collect(Collectors.toMap(PaymentOrder::getBizOrderNo, Function.identity()));

        // 正向：逐笔核对本地成功单
        for (PaymentOrder local : localOrders) {
            BillRecord record = channelRecords.get(local.getBizOrderNo());
            if (record == null) {
                diffs.add(ReconciliationItem.of(batchNo, DiffType.LOCAL_MORE,
                        local.getBizOrderNo(), local.getAmount().getAmountMinor(), null,
                        "我方成功但渠道账单缺失"));
            } else if (record.getAmountMinor() != local.getAmount().getAmountMinor()) {
                diffs.add(ReconciliationItem.of(batchNo, DiffType.AMOUNT_MISMATCH,
                        local.getBizOrderNo(), local.getAmount().getAmountMinor(), record.getAmountMinor(),
                        "金额不一致"));
            }
        }

        // 反向：渠道账单中存在但我方无成功单 → 疑似掉单
        for (BillRecord record : channelRecords.values()) {
            if (!localMap.containsKey(record.getOurTradeNo())) {
                diffs.add(ReconciliationItem.of(batchNo, DiffType.CHANNEL_MORE,
                        record.getOurTradeNo(), null, record.getAmountMinor(),
                        "渠道成功但我方未终态(疑似掉单)"));
            }
        }
        return diffs;
    }
}
