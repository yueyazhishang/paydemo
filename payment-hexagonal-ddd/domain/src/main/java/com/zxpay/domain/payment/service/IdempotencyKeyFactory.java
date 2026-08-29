package com.zxpay.domain.payment.service;

import com.zxpay.domain.channel.model.ChannelCode;
import com.zxpay.domain.payment.model.PaymentOrderId;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * 通道幂等键工厂。
 *
 * <p><b>核心设计：幂等键必须确定性生成，不能用 UUID。</b>
 *
 * <p>这是支付系统里最反直觉、也最容易出错的一条。常见写法是：
 * <pre>{@code
 * String key = UUID.randomUUID().toString();
 * attempt.setIdempotencyKey(key);
 * channelPort.pay(request);      // 调用后进程崩溃
 * // 重试时：又生成一个新 key → 通道视为全新交易 → 重复扣款
 * }</pre>
 *
 * <p>问题在于：生成 key、持久化、调用通道，这三步不是原子的。
 * 进程随时可能在中间挂掉。用随机 key，一旦丢失就永远找不回来，
 * 重试必然产生第二笔交易。
 *
 * <p>正确做法：<b>用业务标识确定性推导</b>。同一个（订单，通道）组合
 * 无论在哪台机器、第几次计算，得到的 key 完全相同。
 * 进程崩溃后重试，算出来的还是同一个 key，通道正确识别为重复请求并返回原结果。
 *
 * <p>请款、撤销、关单同理，但要额外带上序号：
 * 一笔授权可能分多次部分请款，每次的幂等键必须不同
 * （否则第二次请款会被当成第一次的重复请求而返回原结果）。
 */
public final class IdempotencyKeyFactory {

    private IdempotencyKeyFactory() {
    }

    /**
     * 下单幂等键。同一订单 + 同一通道恒定不变。
     *
     * <p>注意这里<b>不带尝试序号</b>：同一通道的重试必须复用同一个 key，
     * 这是幂等的本意。序号只在「同一通道真的要发起一笔全新交易」时才需要，
     * 而那种情况在我们的模型里不会发生——重试复用 attempt，切换换的是另一家通道。
     */
    public static String channelPaymentKey(PaymentOrderId orderId, ChannelCode channel) {
        return "pay:" + orderId.value() + ":" + channel.name();
    }

    /** 请款幂等键。带序号以支持多次部分请款。 */
    public static String captureKey(PaymentOrderId orderId, int captureSeq) {
        return "cap:" + orderId.value() + ":" + captureSeq;
    }

    /** 撤销授权幂等键。 */
    public static String voidKey(PaymentOrderId orderId) {
        return "void:" + orderId.value();
    }

    /** 撤销/冲正幂等键。 */
    public static String reverseKey(PaymentOrderId orderId) {
        return "rev:" + orderId.value();
    }

    /** 关单幂等键。 */
    public static String closeKey(PaymentOrderId orderId) {
        return "close:" + orderId.value();
    }

    /**
     * 生成符合通道长度限制的确定性摘要键。
     *
     * <p>部分通道对幂等键有长度限制（Stripe 建议不超过 255 字符，
     * 某些银行接口只接受 32 位）。此时对完整业务键取 SHA-256 前 32 位十六进制，
     * 既保持确定性，又满足长度要求。
     *
     * <p>注意：不要用 hashCode()——它的算法在不同 JDK 版本间不保证稳定，
     * 且碰撞概率远高于 SHA-256。碰撞意味着两笔不同交易共用一个幂等键，
     * 后果是其中一笔被静默吞掉。
     */
    public static String digest(String businessKey, int length) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(businessKey.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format(Locale.ROOT, "%02x", b));
            }
            int end = Math.min(length, hex.length());
            return hex.substring(0, end);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 强制实现，理论上不可达
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
