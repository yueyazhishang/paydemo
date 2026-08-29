package com.zx.payment.channel.domain.port;

import com.zx.payment.channel.domain.model.ChannelCapability;
import com.zx.payment.shared.ChannelCode;

import java.time.Instant;
import java.util.Map;

/**
 * 端口：通道网关（对称防腐层）。
 *
 * ==================== v1 的错误：防腐层被劈成两半 ====================
 *
 *   v1 里：
 *     出站（领域对象 → 通道报文）定义在基础设施层，叫 ChannelGateway ✅
 *     入站（通道报文 → 领域对象）定义在【应用层】，叫 ChannelNotifyParser ❌
 *
 *   后果：应用层被迫干基础设施的活——验签、解析 JSON/XML、映射字段。
 *   应用层应该只看到"已经翻译好的领域命令"，而不是原始报文。
 *   这个错位还导致入站翻译无法被通道适配器复用（出站和入站是同一个通道的
 *   两套协议，理应放在一起维护才不会走样）。
 *
 * ==================== v2：出站入站对称 ====================
 *
 *   同一个端口，两个方向，都由通道适配器实现：
 *     sendCommand()  —— 出站：领域命令 → 通道协议（签名、序列化、HTTP）
 *     translateNotification() —— 入站：通道报文 → 领域回执（验签、解析、归一化）
 *
 *   两者放在一起，是因为它们是一对：同一通道的签名算法、字段命名、
 *   状态枚举必须在两个方向上保持一致。v1 把它们拆到两层，改一处容易漏另一处。
 */
public interface ChannelPort {

    ChannelCode channel();

    /** 通道能力声明。调用方据此决定"能不能这么干"，而不是靠 if 判断通道类型。 */
    ChannelCapability capability();

    // ==================== 出站：领域 → 通道 ====================

    /**
     * 发送通道指令。
     *
     * @param command 领域语言描述的指令（不含任何通道协议细节）
     * @return 通道回执（领域语言，已归一化）
     */
    ChannelReceipt sendCommand(ChannelCommand command);

    // ==================== 入站：通道 → 领域 ====================

    /**
     * 翻译通道异步通知【并验签】。
     *
     * 验签必须在防腐层内完成——这是 v1 的另一个错位：验签漏到了应用层。
     * 未验签的报文流入领域层，等于允许任何人伪造"支付成功"通知。
     *
     * @param rawBody 原始报文体（JSON / XML / form-encoded，视通道而定）
     * @param headers 原始请求头（签名、时间戳、证书信息在这里）
     * @return 领域回执
     * @throws com.zx.payment.channel.domain.model.SignatureVerificationException 验签失败
     */
    ChannelReceipt translateNotification(String rawBody, Map<String, String> headers);

    /** 应答串：通道要求的成功应答格式。答错会导致通道持续重发。 */
    String successAck();

    // ==================== 防腐层两侧的语言 ====================

    /**
     * 出站指令（领域语言）。不含任何通道协议细节——那部分由适配器补上。
     */
    record ChannelCommand(
            String paymentId,
            String merchantOrderNo,
            CommandType type,
            long amountMinor,
            String currency,
            /** 幂等键：透传给支持幂等的通道（Stripe Idempotency-Key、PayPal Request-Id）。 */
            String idempotencyKey,
            /** 重试场景下的原交易号（退款、关单需要）。 */
            String originalTradeNo
    ) {
        public enum CommandType { PREPAY, QUERY, REFUND, QUERY_REFUND, CLOSE }
    }

    /**
     * 通道回执（领域语言，已归一化）。
     *
     * 无论底层是微信的 trade_state、Stripe 的 status、还是 WorldPay 的
     * lastEvent，翻译后只有三种语义。领域层永远不需要知道通道的枚举叫什么。
     */
    record ChannelReceipt(
            boolean success,
            /** 归一化状态：SUCCESS / FAILED / PENDING */
            String status,
            String channelTradeNo,
            String payerId,
            long amountMinor,
            String currency,
            String failCode,
            String failReason,
            /** 通道返回的失败是否可重试（决定要不要换通道再来一次）。 */
            boolean retriable,
            Instant finishedAt,
            /** 原始报文，仅用于排障，不参与业务判断。 */
            String rawResponse
    ) {
        public static final String STATUS_SUCCESS = "SUCCESS";
        public static final String STATUS_FAILED = "FAILED";
        public static final String STATUS_PENDING = "PENDING";
    }
}
