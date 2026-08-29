package com.example.payment.interfaces.rest;

import com.example.payment.application.command.RefundCommand;
import com.example.payment.application.dto.RefundOrderDTO;
import com.example.payment.application.service.RefundAppService;
import com.example.payment.shared.ApiResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 退款接口。
 */
@RestController
@RequestMapping("/api/refunds")
@RequiredArgsConstructor
public class RefundController {

    private final RefundAppService refundAppService;

    /** 发起退款（支持部分退款，可退额度由领域服务校验） */
    @PostMapping
    public ApiResult<RefundOrderDTO> refund(@Valid @RequestBody RefundCommand command) {
        return ApiResult.ok(refundAppService.refund(command));
    }

    @GetMapping("/{refundId}")
    public ApiResult<RefundOrderDTO> getRefund(@PathVariable String refundId) {
        return ApiResult.ok(refundAppService.getRefund(refundId));
    }
}
