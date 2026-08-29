package com.demo.payment.application.command;

import com.demo.payment.application.outbox.OutboxService;
import com.demo.payment.domain.acquiring.model.aggregate.PaymentOrder;
import com.demo.payment.domain.acquiring.repository.PaymentOrderRepository;
import com.demo.payment.domain.channel.model.ChannelCode;
import com.demo.payment.domain.channel.model.ChannelResultStatus;
import com.demo.payment.domain.channel.spi.*;
import com.demo.payment.shared.money.Money;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;

/**
 * 回调通知处理服务。
 *
 * <h3>核心原则：回调不可信</h3>
 * <p>异步回调存在四类问题，必须逐一应对：
 * <ol>
 *   <li><b>可能丢失</b>：网络抖动、我方 5xx → 依赖主动查证补偿</li>
 *   <li><b>可能重复</b>：通道重投 → <b>notifyId 去重</b></li>
 *   <li><b>可能乱序</b>：先成功回调后失败回调 → <b>状态机终态守卫</b></li>
 *   <li><b>可能被伪造</b>：攻击者构造回调 → <b>严格验签 + 金额比对</b></li>
 * </ol>
 *
 * <p>因此正确处理姿势是：
 * <pre>
 *   收到回调 → 验签 → 去重 → <b>主动查证</b> → 用查证结果更新状态
 * </pre>
 *
 * <p><b>注意第 3 步</b>：生产环境应当"回调只当触发器，状态以查证为准"。
 * 本实现为演示清晰起见直接用回调内容更新，
 * 但保留了 reconcile 分支，并在注释中说明生产建议。
 */
public class NotificationService {

    private final PaymentOrderRepository repository;
    private final java.util.Map<ChannelCode, PaymentChannelPort> channels;
    private final OutboxService outboxService;

    /**
     * 已处理的通知 ID 集合（notifyId 去重）。
     *
     * <p>生产环境应持久化到 Redis（带 TTL）或 DB，
     * 因为进程重启后内存去重表会丢失，导致重启后重复处理通知。
     */
    private final Set<String> processedNotifyIds = ConcurrentHashMap.newKeySet();

    public NotificationService(PaymentOrderRepository repository,
                               java.util.Map<ChannelCode, PaymentChannelPort> channels,
                               OutboxService outboxService) {
        this.repository = repository;
        this.channels = channels;
        this.outboxService = outboxService;
    }

    /**
     * 处理通道回调。
     *
     * @param channelCode 通道编码（由 URL 路径决定，如 /notify/wechatpay）
     * @param raw         原始报文（<b>必须是未解析的原始字符串</b>，否则无法验签）
     * @return 返回给通道的响应文本（微信/支付宝要求特定格式，否则会不断重投）
     */
    public NotifyHandleResult handle(ChannelCode channelCode, RawNotification raw) {
        PaymentChannelPort channel = channels.get(channelCode);
        if (channel == null) {
            throw new IllegalArgumentException("未注册的通道: " + channelCode);
        }

        // ---- 步骤一：解析 + 验签（验签在适配器内部完成，失败直接抛异常）----
        NotificationParseResult parsed = channel.parseNotification(raw);

        // ---- 步骤二：notifyId 去重 ----
        if (parsed.notifyId() != null && !processedNotifyIds.add(parsed.notifyId())) {
            return NotifyHandleResult.duplicate(parsed.notifyId());
        }

        // ---- 步骤三：定位订单 ----
        PaymentOrder order = repository.findByOutTradeNo(parsed.outTradeNo())
                .orElseThrow(() -> new IllegalStateException(
                        "回调对应的订单不存在: " + parsed.outTradeNo()));

        // ---- 步骤四：加锁后应用状态 ----
        Lock lock = repository.obtainLock(order.id());
        lock.lock();
        try {
            boolean changed;
            if (parsed.hasFinalResult()) {
                boolean success = parsed.status() == ChannelResultStatus.SUCCEEDED;
                // 金额一致性由聚合内部强制校验，篡改金额会直接抛异常
                changed = order.applyChannelResult(parsed.outTradeNo(), success,
                        parsed.amount(), parsed.channelTransactionId(),
                        parsed.channelRawStatus(), parsed.occurredAt());
            } else {
                // 回调只是中间态（如"用户支付中"），不更新状态，仅记录
                changed = false;
            }

            if (changed) {
                repository.save(order);
                outboxService.capture("PaymentOrder", order.id().value(), order.pullDomainEvents());
            }
            return NotifyHandleResult.success(parsed.notifyId(), changed);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 返回给通道的应答文本。
     *
     * <p><b>各通道要求不同，返回错会导致通道疯狂重投：</b>
     * <pre>
     *   微信 v3  → HTTP 200 + {"code":"SUCCESS"}，或 204
     *   支付宝   → 纯字符串 "success"（不能带引号、不能有空格）
     *   Stripe   → HTTP 200 即可
     *   PayPal   → HTTP 200
     * </pre>
     */
    public String successResponse(ChannelCode channelCode) {
        return switch (channelCode) {
            case ALIPAY -> "success";
            case WECHAT_PAY -> "{\"code\":\"SUCCESS\",\"message\":\"成功\"}";
            default -> "OK";
        };
    }
}
