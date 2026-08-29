package com.zxpay.domain.payment.model;

/**
 * 终端类型：用户从哪里发起支付。
 *
 * <p>终端决定了可用的交互形态，是路由的输入之一：
 * PC 端不可能拉起微信 APP 支付，APP 内也不该跳 H5 收银台。
 * 这些信息必须在下单时就随请求带上来，否则只能靠 UA 猜——猜错就是转化率的直接损失。
 */
public enum TerminalType {

    /** 商户自有 App。可拉起通道 SDK，体验最好，成功率最高。 */
    APP("移动应用"),

    /** 手机浏览器。只能用 H5 跳转或扫码。 */
    H5("手机网页"),

    /** PC 浏览器。只能扫码或跳转收银台。 */
    WEB("桌面网页"),

    /** 小程序。只能用对应生态的 JSAPI/小程序支付。 */
    MINI_PROGRAM("小程序"),

    /** 线下 POS / 收银机。典型场景是扫码枪扫用户付款码。 */
    POS("线下终端"),

    /** 服务端直连，无前端。常见于代扣、续费、开放 API。 */
    API("服务端"),

    /** 线下静态码，用户主扫。 */
    OFFLINE_QR("线下码牌"),
    ;

    private final String displayName;

    TerminalType(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
