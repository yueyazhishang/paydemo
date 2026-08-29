package com.demo.payment.domain.channel.spi;

import java.util.Map;

/**
 * 通道原始通知报文。
 *
 * <p>这是适配层的输入，保留最原始的信息（body + headers），
 * 因为<b>验签必须基于原始字节流</b> —— 任何先反序列化再验签的做法都是错的：
 * JSON 序列化/反序列化会改变字节序、空格、字段顺序，导致签名校验失败，
 * 更糟的是有人为此"临时"关掉验签，直接把系统敞开给攻击者。
 */
public record RawNotification(
        String body,
        Map<String, String> headers,
        Map<String, String> queryParams,
        String remoteIp
) {
    public static RawNotification of(String body, Map<String, String> headers) {
        return new RawNotification(body, headers, Map.of(), null);
    }

    public String header(String name) {
        return headers == null ? null : headers.get(name);
    }

    /** 大小写不敏感地取 header（HTTP header 名不区分大小写，各通道写法还不同） */
    public String headerIgnoreCase(String name) {
        if (headers == null) { return null; }
        return headers.entrySet().stream()
                .filter(e -> e.getKey() != null && e.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }
}
