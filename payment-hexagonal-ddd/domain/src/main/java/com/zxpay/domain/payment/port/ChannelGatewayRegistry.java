package com.zxpay.domain.payment.port;

import com.zxpay.domain.channel.model.ChannelCode;
import com.zxpay.domain.notify.port.ChannelNotifyParser;
import com.zxpay.domain.notify.port.ChannelNotifyVerifier;
import com.zxpay.domain.refund.port.ChannelRefundPort;
import com.zxpay.domain.refund.port.ChannelRefundQueryPort;

import java.util.Optional;

/**
 * 出站端口：通道端口注册表。
 *
 * <p>把「通道 → 该通道实现了哪些能力端口」的映射也做成端口，
 * 而不是让领域层直接注入一堆 List 然后自己过滤。好处：
 * <ol>
 *   <li><b>领域层保持纯粹</b>。它只表达「我需要 A 通道的请款能力」，
 *       不关心实现是 Spring Bean、ServiceLoader 还是手写 Map。</li>
 *   <li><b>缺失可表达</b>。返回 {@code Optional} 而不是抛异常，
 *       让上层能优雅降级：没有请款端口就说明该通道不支持请款。</li>
 *   <li><b>可测试</b>。单测里塞一个 HashMap 实现即可覆盖所有组合。</li>
 * </ol>
 *
 * <p>基础设施层实现时，通常是扫描所有 {@link ChannelPort} 实现类，
 * 按 {@code channel()} 建索引。Spring 环境下直接注入
 * {@code List<ChannelPaymentPort>} 即可自动收齐。
 */
public interface ChannelGatewayRegistry {

    Optional<ChannelPaymentPort> paymentPortOf(ChannelCode channel);

    Optional<ChannelQueryPort> queryPortOf(ChannelCode channel);

    Optional<ChannelCapturePort> capturePortOf(ChannelCode channel);

    Optional<ChannelVoidPort> voidPortOf(ChannelCode channel);

    Optional<ChannelReversePort> reversePortOf(ChannelCode channel);

    Optional<ChannelClosePort> closePortOf(ChannelCode channel);

    /**
     * 该通道的回调验签器。
     *
     * <p>每家通道的验签方式都不同，领域层只要求「给我一个能验签的东西」，
     * 不关心它是本地 RSA 验签还是需要反向调用 PayPal 接口。
     */
    Optional<ChannelNotifyVerifier> verifierOf(ChannelCode channel);

    /** 该通道的回调解析器。 */
    Optional<ChannelNotifyParser> parserOf(ChannelCode channel);

    /** 该通道的退款端口。只有支持退款的通道才注册。 */
    Optional<ChannelRefundPort> refundPortOf(ChannelCode channel);

    /** 该通道的退款查询端口。用于退款通知丢失时主动查询。 */
    Optional<ChannelRefundQueryPort> refundQueryPortOf(ChannelCode channel);

    /** 该通道是否至少注册了一个可用端口。用于启动时校验配置完整性。 */
    default boolean hasAnyPort(ChannelCode channel) {
        return paymentPortOf(channel).isPresent()
                || queryPortOf(channel).isPresent()
                || capturePortOf(channel).isPresent()
                || voidPortOf(channel).isPresent()
                || reversePortOf(channel).isPresent()
                || closePortOf(channel).isPresent();
    }
}
