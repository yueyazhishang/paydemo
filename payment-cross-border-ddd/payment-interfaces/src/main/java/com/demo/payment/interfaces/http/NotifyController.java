package com.demo.payment.interfaces.http;

import com.demo.payment.application.command.NotificationService;
import com.demo.payment.application.command.NotifyHandleResult;
import com.demo.payment.domain.channel.model.ChannelCode;
import com.demo.payment.domain.channel.spi.RawNotification;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * 通道回调接入层。
 *
 * <h3>两个铁律</h3>
 * <ol>
 *   <li><b>必须拿原始 body 验签</b>：不能用 Spring 的 {@code @RequestBody Map}
 *       反序列化后的对象再验签 —— 序列化过程会改变空格、字段顺序，
 *       导致签名不匹配。因此这里用 {@code String} 接收原始报文。</li>
 *   <li><b>必须返回通道要求的应答格式</b>：否则通道会判定通知失败并持续重投。
 *       支付宝要求返回纯文本 {@code success}，微信要求 JSON。
 *       返回错格式会导致通知被重投 8 次以上，日志里全是重复告警。</li>
 * </ol>
 */
@RestController
@RequestMapping("/notify")
public class NotifyController {

    private final NotificationService notificationService;

    public NotifyController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /**
     * 统一回调入口：{@code POST /notify/{channel}}
     *
     * <p>注意 {@code produces}：支付宝要求 {@code text/plain} 返回 "success"，
     * 若返回 JSON 会一直重投。
     */
    @PostMapping(value = "/{channel}", produces = "text/plain;charset=UTF-8")
    public ResponseEntity<String> notify(@PathVariable("channel") String channel,
                                         @RequestBody String rawBody,
                                         @RequestHeader Map<String, String> headers,
                                         @RequestParam Map<String, String> queryParams) {
        ChannelCode channelCode = ChannelCode.valueOf(channel.toUpperCase());

        RawNotification raw = new RawNotification(rawBody, headers, queryParams, null);

        NotifyHandleResult result;
        try {
            result = notificationService.handle(channelCode, raw);
        } catch (Exception e) {
            // 验签失败/订单不存在等情况：返回 5xx 让通道重投，同时记录告警
            // 注意：订单不存在时返回 5xx 会导致通道无限重投，
            // 生产环境应区分对待 —— 验签失败返回 4xx，订单不存在返回 200 并记录死信
            return ResponseEntity.status(500).body("ERROR: " + e.getMessage());
        }

        // 返回通道要求的成功应答
        return ResponseEntity.ok(notificationService.successResponse(channelCode));
    }
}
