package com.example.payment.domain.gateway;

/**
 * 上游业务方通知端口（防腐层 Port）。
 * 支付/退款终态后由应用层调用，基础设施层以 HTTP 实现（附签名头）。
 * 端口化便于单测 mock 与将来替换为 MQ 通知。
 */
public interface UpstreamNotifier {

    /**
     * 通知上游业务方支付/退款结果。
     *
     * @param notifyUrl 上游回调地址
     * @param payload   通知报文 JSON
     * @return 上游应答是否成功（HTTP 200 且 body 约定成功标记）
     * @throws GatewayException 网络异常/超时（由实现抛出，调用方按失败重试）
     */
    boolean notify(String notifyUrl, String payload);
}
