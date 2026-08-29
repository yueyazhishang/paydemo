package com.example.payment.application.service;

import com.example.payment.application.dto.ReconciliationResultDTO;
import com.example.payment.domain.gateway.BillDownloader;
import com.example.payment.domain.payment.model.PaymentOrder;
import com.example.payment.domain.payment.model.PaymentStatus;
import com.example.payment.domain.payment.repository.PaymentOrderRepository;
import com.example.payment.domain.reconciliation.model.ReconciliationBatch;
import com.example.payment.domain.reconciliation.model.ReconciliationItem;
import com.example.payment.domain.reconciliation.repository.ReconciliationBatchRepository;
import com.example.payment.domain.reconciliation.repository.ReconciliationItemRepository;
import com.example.payment.domain.service.GatewayRegistry;
import com.example.payment.domain.service.ReconciliationDomainService;
import com.example.payment.domain.shared.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 对账应用服务：纯用例编排（备数据 → 调领域服务核对 → 落库），
 * 核对规则（LOCAL_MORE/CHANNEL_MORE/AMOUNT_MISMATCH 判定）全部收敛在
 * {@link ReconciliationDomainService}，应用层不含任何业务规则。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReconciliationAppService {

    private final GatewayRegistry gatewayRegistry;
    private final PaymentOrderRepository paymentOrderRepository;
    private final ReconciliationBatchRepository batchRepository;
    private final ReconciliationItemRepository itemRepository;

    @Transactional
    public ReconciliationResultDTO reconcile(String channelName, LocalDate billDate) {
        Channel channel = Channel.valueOf(channelName);

        // 幂等：同批次重复执行直接返回既有结果
        ReconciliationBatch batch = ReconciliationBatch.create(channel.name(), billDate);
        var existing = batchRepository.findByBatchNo(batch.getBatchNo());
        if (existing.isPresent()) {
            return toResult(existing.get());
        }

        // 1. 备数据·渠道侧：下载渠道 T+1 账单（防腐层端口）
        batch.startDownloading();
        BillDownloader downloader = gatewayRegistry.getBillDownloader(channel);
        Map<String, com.example.payment.domain.gateway.BillRecord> channelRecords =
                downloader.download(billDate).stream()
                        .collect(Collectors.toMap(
                                com.example.payment.domain.gateway.BillRecord::getOurTradeNo,
                                Function.identity(), (a, b) -> a));

        // 2. 备数据·本地侧：该渠道当日成功的支付单
        batch.startChecking();
        List<PaymentOrder> localOrders = paymentOrderRepository.findByStatus(PaymentStatus.SUCCESS).stream()
                .filter(o -> o.getChannel() == channel)
                .toList();

        // 3. 领域服务执行核对（业务规则所在）
        List<ReconciliationItem> diffs =
                ReconciliationDomainService.reconcile(batch.getBatchNo(), localOrders, channelRecords);

        // 4. 落库
        batch.complete(localOrders.size(), channelRecords.size(), diffs);
        batchRepository.save(batch);
        itemRepository.saveAll(diffs);
        if (batch.hasDiscrepancy()) {
            log.warn("对账发现差异: batchNo={}, diffCount={}", batch.getBatchNo(), batch.getDiffCount());
        }
        return toResult(batch);
    }

    private ReconciliationResultDTO toResult(ReconciliationBatch batch) {
        return ReconciliationResultDTO.builder()
                .batchNo(batch.getBatchNo()).channel(batch.getChannel()).billDate(batch.getBillDate())
                .localCount(batch.getLocalCount()).channelCount(batch.getChannelCount())
                .diffCount(batch.getDiffCount()).hasDiscrepancy(batch.hasDiscrepancy())
                .build();
    }
}
