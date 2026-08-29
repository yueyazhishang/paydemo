package com.example.payment.infrastructure.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 支付渠道配置（对应 application.yml 的 pay.* 配置段）。
 * 渠道私有凭证只在基础设施层可见，不得上浮到领域/应用层。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "pay")
public class PayProperties {

    /** 对外服务的回调地址前缀，如 http://localhost:8080 */
    private String notifyBaseUrl;

    /** 各渠道私有配置（key 与 application.yml pay.channels.* 对应） */
    private Map<String, Map<String, String>> channels = new HashMap<>();

    public String getChannelConfig(String channelKey, String field) {
        return channels.getOrDefault(channelKey, Map.of()).get(field);
    }
}
