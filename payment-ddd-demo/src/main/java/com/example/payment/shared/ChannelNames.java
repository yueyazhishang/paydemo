package com.example.payment.shared;

import com.example.payment.domain.shared.Channel;

/**
 * 渠道路径参数解析工具：支持大写枚举名与常见小写别名，
 * 如 "WECHAT_PAY" / "wechat_pay" / "stripe"。
 */
public final class ChannelNames {

    private ChannelNames() {
    }

    public static Channel parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("渠道参数不能为空");
        }
        String normalized = raw.trim().toUpperCase().replace('-', '_');
        try {
            return Channel.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知渠道: " + raw);
        }
    }
}
