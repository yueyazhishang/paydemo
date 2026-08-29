package com.example.payment.interfaces.rest;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模拟上游业务方的结果接收端点（仅用于演示通知闭环）：
 * 业务方下单时在 merchantNotifyUrl 中填入 http://localhost:8080/api/mock/merchant-notify，
 * 支付成功/关单/退款成功后，本端点将收到支付平台的通知，应答 "success" 表示送达。
 */
@Slf4j
@RestController
@RequestMapping("/api/mock")
public class MerchantNotifyMockController {

    @PostMapping(value = "/merchant-notify", produces = MediaType.TEXT_PLAIN_VALUE)
    public String onNotify(@RequestBody String payload) {
        log.info("[模拟业务方] 收到支付平台通知: {}", payload);
        return "success";
    }
}
