package com.demo.payment.shared.util;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ID 生成器。
 *
 * <p><b>支付系统对单号的特殊要求：</b>
 * <ul>
 *   <li><b>全局唯一</b>：outTradeNo 在通道侧唯一，重复会直接导致下单失败或串单。</li>
 *   <li><b>不可猜测</b>：订单号暴露在 URL 里，可被遍历就是信息泄露。
 *       纯自增 ID 会让竞争对手通过订单号推算你的日交易量。</li>
 *   <li><b>含时间前缀</b>：便于按时间范围分库分表、排查问题、DBA 做分区裁剪。</li>
 *   <li><b>长度可控</b>：微信 out_trade_no 限 32 位，支付宝限 64 位，需留足余量。</li>
 * </ul>
 *
 * <p>本实现采用「时间戳 + 序列 + 随机数」组合，单机可用；
 * 生产环境建议替换为号段模式（Leaf / TinyID），避免多机时钟回拨问题。
 */
public final class IdGenerator {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);
    private static final AtomicLong SEQUENCE = new AtomicLong(0);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long MAX_SEQUENCE = 999_999L;

    private IdGenerator() {}

    /** 支付单号：P + 17位时间戳 + 6位序列 + 4位随机 = 28 位 */
    public static String paymentOrderId() {
        return "P" + TS.format(Instant.now()) + seq() + rand(4);
    }

    /**
     * 发往通道的订单号。
     *
     * <p><b>关键设计：同一支付单多次尝试必须生成不同的 outTradeNo。</b>
     * 微信/支付宝的 out_trade_no 是全局唯一的，若重试时复用同一个号，
     * 第二次下单会返回"订单已存在"，导致重试永远失败。
     * 因此这里把 attemptSeq 编进单号，天然保证唯一。
     */
    public static String outTradeNo(String paymentOrderId, int attemptSeq) {
        return paymentOrderId + "A" + attemptSeq;
    }

    /** 退款单号：R + 时间戳 + 序列 + 随机 */
    public static String refundOrderId() {
        return "R" + TS.format(Instant.now()) + seq() + rand(4);
    }

    /** 幂等键（客户端未提供时服务端生成） */
    public static String idempotencyKey() {
        return "IK" + TS.format(Instant.now()) + rand(8);
    }

    private static String seq() {
        return String.format("%06d", SEQUENCE.updateAndGet(v -> v >= MAX_SEQUENCE ? 1 : v + 1));
    }

    private static String rand(int digits) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < digits; i++) {
            sb.append(RANDOM.nextInt(10));
        }
        return sb.toString();
    }
}
