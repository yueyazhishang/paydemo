package com.zxpay.domain.notify.port;

import com.zxpay.domain.notify.model.NotificationEnvelope;
import com.zxpay.domain.notify.model.NotificationPayload;

import java.util.Optional;

/**
 * 出站端口：把通道回调报文解析成归一化内容。
 *
 * <p>只在验签通过之后调用。解析器每家通道一个实现，
 * 负责把自家那套字段（微信的 {@code resource.ciphertext} 需 AEAD 解密、
 * 支付宝的 {@code trade_status}、Stripe 的 {@code type} 事件类型）
 * 翻译成统一的 {@link NotificationPayload}。
 *
 * <p>为什么解析也要做端口而不是在 controller 里直接写：
 * 通道报文结构的差异是最大的，把这部分关在适配器里，
 * 业务层才能做到「换通道不改一行」。
 */
public interface ChannelNotifyParser {

    /**
     * 解析报文。
     *
     * @return 解析结果；报文结构异常或状态无法映射时返回空
     */
    Optional<NotificationPayload> parse(NotificationEnvelope envelope);
}
