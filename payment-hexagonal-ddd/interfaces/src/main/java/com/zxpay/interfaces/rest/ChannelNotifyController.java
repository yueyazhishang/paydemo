package com.zxpay.interfaces.rest;

import com.zxpay.application.dto.PaymentCommands;
import com.zxpay.application.notify.ChannelNotifyApplicationService;
import com.zxpay.domain.channel.model.ChannelCode;
import com.zxpay.domain.notify.model.NotificationEnvelope;
import com.zxpay.sharedkernel.time.ClockHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 通道回调网关（入站适配器）。
 *
 * <p>这是本系统<b>唯一暴露给公网且无需鉴权</b>的入口——
 * 通道不认识我们的账号体系，只能用签名证明身份。
 * 因此这个 Controller 的安全性至关重要。
 *
 * <h3>三条铁律</h3>
 * <ol>
 *   <li><b>必须用原始报文体验签</b>。
 *       不能用 {@code @RequestBody SomeDto} 让 Spring 反序列化后再验签——
 *       反序列化会丢失原始字节顺序与空格，签名必然校验失败。
 *       这是接微信/支付宝回调时最常见的坑。</li>
 *   <li><b>业务失败也要返回 2xx</b>。
 *       返回 5xx 会让通道按重试策略反复推送，同一笔问题被放大十几次。
 *       处理不了的情况应当落库、告警，交给补偿任务。</li>
 *   <li><b>验签失败返回 401</b>。
 *       这与上一条不矛盾：验签失败通常意味着有人在伪造回调，
 *       属于安全事件，必须明确拒绝并告警。</li>
 * </ol>
 */
@RestController
@RequestMapping("/callback")
public class ChannelNotifyController {

    private final ChannelNotifyApplicationService notifyService;

    public ChannelNotifyController(ChannelNotifyApplicationService notifyService) {
        this.notifyService = notifyService;
    }

    /**
     * 接收通道回调。
     *
     * <p>路径按通道区分（{@code /callback/wechat_pay}、{@code /callback/stripe} ...），
     * 好处是：可以在网关层按路径做限流与告警，
     * 也便于在日志里一眼看出是哪家通道在推送。
     */
    @PostMapping("/{channel}")
    public ResponseEntity<Map<String, String>> handle(
            @PathVariable String channel,
            @RequestBody String rawBody,
            @RequestHeader Map<String, String> headers) {

        ChannelCode channelCode;
        try {
            channelCode = ChannelCode.of(channel);
        } catch (IllegalArgumentException e) {
            // 未知通道：拒绝。避免被当成开放的回显接口探测。
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("code", "UNKNOWN_CHANNEL", "message", channel));
        }

        NotificationEnvelope envelope =
                NotificationEnvelope.of(channelCode, headers, rawBody, ClockHolder.now());

        PaymentCommands.NotifyHandleResult result = notifyService.handle(envelope);

        if (!result.signatureValid()) {
            // 验签失败：安全事件，明确拒绝
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("code", "SIGNATURE_INVALID", "message", String.valueOf(result.message())));
        }

        // 其余情况一律返回成功：通道不必重试，问题由我方补偿任务处理
        return ResponseEntity.ok(Map.of(
                "code", "SUCCESS",
                "message", String.valueOf(result.message())));
    }
}
