package com.zxpay.domain.payment.model;

/**
 * 支付场景：这笔交易发生在什么样的上下文中。
 *
 * <p>三个作用：
 * <ol>
 *   <li><b>路由输入</b>：终端类型决定可用的交互形态（APP 不能走扫码）。</li>
 *   <li><b>风控要素</b>：IP、设备指纹、地区是实时风控最基本的输入。
 *       缺少这些，风控模型就只能瞎猜，最终退化为「一律放行」或「一律拦截」。</li>
 *   <li><b>合规留存</b>：跨境支付需要记录交易发生地与用户 IP，
 *       用于反洗钱与制裁名单筛查，属于监管硬性要求。</li>
 * </ol>
 *
 * <p>{@code countryCode} 用 ISO 3166-1 alpha-2。注意中国香港/中国台湾/中国澳门
 * 分别使用 HK / TW / MO，属于国家代码下的地区代码，不是独立国家。
 */
public record PaymentScene(
        TerminalType terminal,

        /** 用户客户端 IP。风控与合规必需。 */
        String clientIp,

        /** 设备指纹 / 设备号。用于设备维度风控与拒付举证。 */
        String deviceId,

        /** 交易发生地，ISO 3166-1 alpha-2。 */
        String countryCode,

        String userAgent,

        /** 商户侧用户标识，用于跨会话行为分析与拒付举证。 */
        String buyerAccountId
) {

    public PaymentScene {
        if (terminal == null) {
            throw new IllegalArgumentException("terminal must not be null");
        }
    }

    public static PaymentScene of(TerminalType terminal, String clientIp, String countryCode) {
        return new PaymentScene(terminal, clientIp, null, countryCode, null, null);
    }
}
