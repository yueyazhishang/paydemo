package com.zxpay.infrastructure.notify;

import com.zxpay.domain.notify.model.MerchantNotifyTask;
import com.zxpay.domain.notify.port.MerchantNotifier;
import com.zxpay.sharedkernel.time.ClockHolder;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 商户通知投递器的演示实现。
 *
 * <p>生产实现要做四件事：
 * <ol>
 *   <li><b>签名</b>。商户收到通知后要能验证「这确实来自支付平台」，
 *       否则商户侧同样存在伪造风险。</li>
 *   <li><b>超时</b>。商户接口慢会拖垮通知线程池，进而影响所有商户的通知时效。
 *       建议连接 3 秒、读取 5 秒。</li>
 *   <li><b>结果判定</b>。只有明确的 2xx 才算成功。
 *       4xx 通常意味着商户接口有问题（参数错、地址不存在），
 *       重试意义不大，应降级为告警而非无限重试。</li>
 *   <li><b>递增间隔重试</b>。1m / 5m / 30m / 2h / 6h / 24h，
 *       避免持续冲击故障中的商户服务。重试策略在
 *       {@code MerchantNotifyTask#nextAttempt} 里定义。</li>
 * </ol>
 */
@Component
public class LoggingMerchantNotifier implements MerchantNotifier {

    @Override
    public NotifyOutcome notify(MerchantNotifyTask task) {
        Instant now = ClockHolder.now();

        if (task.notifyUrl() == null || task.notifyUrl().isBlank()) {
            return NotifyOutcome.failure(null, "no notify url configured", false, now);
        }

        // 演示实现：模拟投递成功。真实实现在这里发 HTTP POST 并带签名。
        System.out.printf("[merchant-notify] url=%s event=%s order=%s attempt=%d%n",
                task.notifyUrl(), task.eventType(), task.merchantOrderNo(), task.attemptNo());

        return NotifyOutcome.success(200, "{\"code\":\"SUCCESS\"}", now);
    }
}
