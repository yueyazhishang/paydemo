package com.zxpay.domain.channel.model;

/**
 * 支付交互形态：用户是如何「确认付款」的。
 *
 * <p>这是通道抽象里极易被忽略、却直接决定接口返回值形态的一个维度。
 * 同一个「下单」动作，不同交互形态下，返回给商户的东西完全不同：
 *
 * <table border="1">
 *   <tr><th>形态</th><th>典型通道</th><th>下单返回给前端的东西</th></tr>
 *   <tr><td>SDK 唤起</td><td>微信 JSAPI / 支付宝 APP</td><td>prepay_id 及签名参数，前端唤起收银台</td></tr>
 *   <tr><td>二维码</td><td>微信 NATIVE / 支付宝当面付</td><td>code_url，前端渲染成二维码</td></tr>
 *   <tr><td>跳转</td><td>支付宝 PC 网站 / PayPal / 3DS</td><td>302 到通道收银台 URL</td></tr>
 *   <tr><td>条码</td><td>微信付款码 / 支付宝条码</td><td>无，后台直扣，需处理 USERPAYING</td></tr>
 *   <tr><td>纯 API</td><td>卡 / 已保存的 PayPal Vault</td><td>无前端动作，同步返回终态</td></tr>
 *   <tr><td>异步指令</td><td>银行转账 / SEPA</td><td>收款账户信息，等用户线下转账</td></tr>
 * </table>
 *
 * <p>如果在领域层不区分这些，应用层就会被迫写
 * {@code if (channel == WECHAT) return codeUrl; else if (channel == STRIPE) return clientSecret;}
 * ——这正是本 Demo 要用能力矩阵消灭的 if-else 沼泽。
 */
public enum InteractionMode {

    /** 前端 SDK 唤起：返回唤起参数（微信 JSAPI 的 prepay、支付宝 APP 的 orderString）。 */
    FRONTEND_SDK("前端SDK唤起"),

    /** 二维码：返回 code_url，由前端渲染。 */
    SCAN_QR("扫码"),

    /** 页面跳转：返回通道收银台 URL，浏览器 302。 */
    REDIRECT("页面跳转"),

    /** 条码/付款码：商户扫用户，后台直扣，用户可能在输入密码（微信 USERPAYING）。 */
    BARCODE("条码支付"),

    /** 纯 API：无前端交互，可同步拿到终态（卡支付、已授权的 Vault 扣款）。 */
    API_ONLY("纯API"),

    /** 异步指令：返回收款信息，等待用户线下完成（银行转账、SEPA）。 */
    ASYNC_INSTRUCTION("异步指令"),
    ;

    private final String displayName;

    InteractionMode(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
