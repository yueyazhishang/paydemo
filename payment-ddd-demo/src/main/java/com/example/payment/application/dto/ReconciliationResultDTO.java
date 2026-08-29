package com.example.payment.application.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;

/**
 * 对账结果 DTO。
 */
@Getter
@Builder
public class ReconciliationResultDTO {

    private String batchNo;
    private String channel;
    private LocalDate billDate;
    private int localCount;
    private int channelCount;
    private int diffCount;
    private boolean hasDiscrepancy;
}
