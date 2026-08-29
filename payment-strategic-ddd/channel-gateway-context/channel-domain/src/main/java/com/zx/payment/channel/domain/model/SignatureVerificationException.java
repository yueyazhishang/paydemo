package com.zx.payment.channel.domain.model;

/**
 * 验签失败。
 *
 * 这是安全边界，不是普通的业务异常：收到伪造的"支付成功"通知而不验签，
 * 等价于允许任何人给自己发货。所以防腐层必须把验签做在【最外层】——
 * 未验签的报文绝不允许进入领域层。
 *
 * 处理策略：拒绝请求 + 告警（验签失败可能是被攻击，也可能是密钥配置错了，
 * 两种情况都需要人工介入）。
 */
public class SignatureVerificationException extends RuntimeException {

    private final String channelCode;

    public SignatureVerificationException(String channelCode, String detail) {
        super(String.format("通道[%s]验签失败：%s", channelCode, detail));
        this.channelCode = channelCode;
    }

    public String channelCode() {
        return channelCode;
    }
}
