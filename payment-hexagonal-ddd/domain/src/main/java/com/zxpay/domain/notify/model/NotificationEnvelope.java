package com.zxpay.domain.notify.model;

import com.zxpay.domain.channel.model.ChannelCode;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;

/**
 * 通道回调原始信封。
 *
 * <p>只包含「通道发来的原始内容」，不做任何业务解释。
 * 这样设计是为了保证<b>验签前不解析、解析前不落业务状态</b>。
 *
 * <p>验签与解析分两步，顺序不能颠倒：
 * <ol>
 *   <li>先用原始字节验签。任何「先解析再验签」的实现都有被伪造报文攻击的风险——
 *       攻击者构造一个字段就能让系统在验签通过前就执行业务逻辑。</li>
 *   <li>验签通过后，才把报文解析成 {@code NotificationPayload}。</li>
 * </ol>
 *
 * <p>此外必须保留原始 body：验签通常是对原始字节做的，
 * 一旦做了 JSON 反序列化再重新序列化，空格、字段顺序的变化会让签名校验失败。
 * 这是接微信/支付宝回调时最常见的坑。
 */
public record NotificationEnvelope(
        ChannelCode channel,

        /** 原始请求头。验签所需的 signature / timestamp / nonce 都在这里。 */
        Map<String, String> headers,

        /** 原始报文体，未经任何加工。 */
        String rawBody,

        /** 我方接收时间。 */
        Instant receivedAt
) {

    public NotificationEnvelope {
        headers = headers == null ? Map.of() : Collections.unmodifiableMap(headers);
    }

    public static NotificationEnvelope of(ChannelCode channel, Map<String, String> headers,
                                          String rawBody, Instant receivedAt) {
        return new NotificationEnvelope(channel, headers, rawBody, receivedAt);
    }

    public String header(String name) {
        return headers.get(name);
    }
}
