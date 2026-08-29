package com.zxpay.domain.notify.port;

import com.zxpay.domain.notify.model.MerchantNotifyTask;

import java.time.Instant;

/**
 * 出站端口：向商户投递支付结果通知。
 *
 * <p>这是六边形右侧的又一个端口——「通知商户」和「调用通道」一样，
 * 是外部依赖，领域层只定义契约，具体实现（HTTP 调用、签名、超时）
 * 全部在基础设施层。
 *
 * <p>实现要点：
 * <ul>
 *   <li><b>必须带签名</b>。商户收到通知后要能验证「这确实来自支付平台」，
 *       否则商户侧同样存在伪造风险。</li>
 *   <li><b>必须有超时</b>。商户接口慢会拖垮我们的通知线程池，
 *       进而影响所有商户的通知时效。通常 3~5 秒。</li>
 *   <li><b>只有明确的 2xx 才算成功</b>。3xx、4xx、5xx、超时一律算失败并重试。
 *       但 4xx 通常意味着商户接口有问题（参数错、地址错），
 *       重试意义不大，应当降级为告警而非无限重试。</li>
 * </ul>
 */
public interface MerchantNotifier {

    NotifyOutcome notify(MerchantNotifyTask task);

    /**
     * 投递结果。
     *
     * @param success      是否投递成功
     * @param httpStatus   商户返回的 HTTP 状态码，超时为 null
     * @param responseBody 商户响应体，截断保存用于排查
     * @param retryable    是否值得重试
     * @param completedAt  投递完成时间
     */
    record NotifyOutcome(boolean success, Integer httpStatus, String responseBody,
                         boolean retryable, Instant completedAt) {

        public static NotifyOutcome success(int status, String body, Instant at) {
            return new NotifyOutcome(true, status, truncate(body), false, at);
        }

        public static NotifyOutcome failure(Integer status, String body, boolean retryable, Instant at) {
            return new NotifyOutcome(false, status, truncate(body), retryable, at);
        }

        private static String truncate(String body) {
            if (body == null) {
                return null;
            }
            return body.length() <= 512 ? body : body.substring(0, 512);
        }
    }
}
