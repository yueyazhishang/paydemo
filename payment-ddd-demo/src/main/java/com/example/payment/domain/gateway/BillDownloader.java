package com.example.payment.domain.gateway;

import com.example.payment.domain.shared.Channel;

import java.time.LocalDate;
import java.util.List;

/**
 * 渠道账单下载端口（对账上下文使用，防腐层）。
 * 各渠道对账文件格式不同（CSV/XML/结算报告 API），统一转换为 {@link BillRecord}。
 */
public interface BillDownloader {

    Channel channel();

    /** 下载指定日期（T+1）的渠道账单 */
    List<BillRecord> download(LocalDate billDate);
}
