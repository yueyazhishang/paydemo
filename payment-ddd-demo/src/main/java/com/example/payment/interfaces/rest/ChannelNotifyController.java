package com.example.payment.interfaces.rest;

import com.example.payment.application.service.ChannelCallbackAppService;
import com.example.payment.domain.gateway.CallbackRequest;
import com.example.payment.domain.gateway.GatewayException;
import com.example.payment.shared.ChannelNames;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 渠道异步通知统一入口：/api/notify/{channel}。
 * 接口层职责：把 HTTP 原始报文包装成 CallbackRequest，再把处理结果翻译为
 * 各渠道要求的应答报文（微信 {"code":"SUCCESS"} / 支付宝 "success" / 其余 HTTP 200）。
 */
@Slf4j
@RestController
@RequestMapping("/api/notify")
@RequiredArgsConstructor
public class ChannelNotifyController {

    private final ChannelCallbackAppService callbackAppService;

    @PostMapping("/{channel}")
    public ResponseEntity<String> onNotify(
            @PathVariable("channel") String channel,
            @RequestHeader Map<String, String> headers,
            @RequestBody String body) {

        // 渠道名解析一次（非法渠道直接返回失败应答，避免 catch 内重复解析二次抛异常）
        com.example.payment.domain.shared.Channel channelEnum;
        try {
            channelEnum = ChannelNames.parse(channel);
        } catch (IllegalArgumentException e) {
            log.warn("未知渠道回调: channel={}", channel);
            return ResponseEntity.internalServerError().body("unknown channel");
        }

        try {
            callbackAppService.handleCallback(CallbackRequest.builder()
                    .channel(channelEnum)
                    .headers(headers)
                    .body(body)
                    .build());
            return buildSuccessAck(channelEnum);
        } catch (GatewayException e) {
            // 验签失败等网关异常：返回渠道要求的失败应答，渠道会按自己的策略重试
            log.warn("渠道回调处理失败: channel={}, error={}", channel, e.getMessage());
            return buildFailureAck(channelEnum);
        } catch (Exception e) {
            // 业务异常(金额不一致/订单不存在/状态冲突)同样返回渠道失败应答：
            // 绝不能把 ApiResult JSON 冒泡给渠道，应答格式错误会干扰渠道重试策略
            log.warn("渠道回调业务异常: channel={}, error={}", channel, e.getMessage());
            return buildFailureAck(channelEnum);
        }
    }

    /** 渠道差异化应答（防腐的接口侧体现） */
    private ResponseEntity<String> buildSuccessAck(com.example.payment.domain.shared.Channel channel) {
        return switch (channel) {
            case WECHAT_PAY -> ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"code\":\"SUCCESS\",\"message\":\"成功\"}");
            case ALIPAY -> ResponseEntity.ok().body("success");
            case JD_PAY -> ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"status\":\"success\"}");
            default -> ResponseEntity.ok().body("success");
        };
    }

    private ResponseEntity<String> buildFailureAck(com.example.payment.domain.shared.Channel channel) {
        return switch (channel) {
            case WECHAT_PAY -> ResponseEntity.internalServerError()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"code\":\"FAIL\",\"message\":\"验签失败\"}");
            case ALIPAY -> ResponseEntity.ok().body("failure");
            default -> ResponseEntity.internalServerError().body("failure");
        };
    }
}
