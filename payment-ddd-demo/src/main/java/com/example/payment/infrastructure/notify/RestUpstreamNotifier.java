package com.example.payment.infrastructure.notify;

import com.example.payment.domain.gateway.GatewayException;
import com.example.payment.domain.gateway.UpstreamNotifier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * 上游通知 HTTP 实现（UpstreamNotifier 端口的防腐 Adapter）。
 *
 * <p>真实实现要点：
 * <ul>
 *   <li>通知报文需携带签名头（如 X-Notify-Sign: HMAC-SHA256(secret, body)），上游可验签防伪造</li>
 *   <li>设置合理超时（连接 3s / 读取 5s），超时按失败处理交给重试机制</li>
 *   <li>上游应答 "success" 才算送达；其余视为失败触发退避重试</li>
 * </ul>
 */
@Slf4j
@Component
public class RestUpstreamNotifier implements UpstreamNotifier {

    private final RestClient restClient;

    public RestUpstreamNotifier() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .build();
    }

    @Override
    public boolean notify(String notifyUrl, String payload) {
        log.info("[上游通知] POST {} body={}", notifyUrl, payload);
        try {
            String response = restClient.post()
                    .uri(notifyUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    // Demo 用固定标记头；真实实现替换为 HMAC 签名
                    .header("X-Notify-Sign", "demo-sign")
                    .body(payload)
                    .retrieve()
                    .body(String.class);
            return "success".equalsIgnoreCase(response == null ? "" : response.trim());
        } catch (Exception e) {
            // 网络异常/非2xx 统一翻译为网关异常（防腐），由重试机制兜底
            throw new GatewayException("上游通知失败: " + e.getMessage(), e);
        }
    }
}
