package com.example.payment.interfaces.rest;

import com.example.payment.application.dto.ReconciliationResultDTO;
import com.example.payment.application.service.ReconciliationAppService;
import com.example.payment.shared.ApiResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 对账接口（Demo 手动触发；生产中由调度任务每日驱动）。
 */
@RestController
@RequestMapping("/api/reconciliation")
@RequiredArgsConstructor
public class ReconciliationController {

    private final ReconciliationAppService reconciliationAppService;

    /** 执行指定渠道指定日期（T+1）的对账，如 POST /api/reconciliation/WECHAT/run?billDate=2026-08-28 */
    @PostMapping("/{channel}/run")
    public ApiResult<ReconciliationResultDTO> run(
            @PathVariable("channel") String channel,
            @RequestParam(name = "billDate", required = false) LocalDate billDate) {
        LocalDate date = billDate != null ? billDate : LocalDate.now().minusDays(1);
        return ApiResult.ok(reconciliationAppService.reconcile(channel, date));
    }
}
