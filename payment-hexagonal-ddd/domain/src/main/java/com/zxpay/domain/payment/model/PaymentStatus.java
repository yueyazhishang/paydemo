package com.zxpay.domain.payment.model;

/**
 * 支付单状态：<b>归一化状态</b>。
 *
 * <p>设计要点：对外只暴露一套状态，屏蔽九家通道各自的命名。
 * 微信是 {@code NOTPAY/USERPAYING/SUCCESS/CLOSED/REVOKED/PAYERROR}，
 * 支付宝是 {@code WAIT_BUYER_PAY/TRADE_SUCCESS/TRADE_CLOSED/TRADE_FINISHED}，
 * Stripe 是 {@code requires_payment_method/requires_action/processing/succeeded}。
 * 如果让这些原始状态渗透到业务代码里，上层会变成一张巨大的翻译表。
 *
 * <p>同时必须保留<b>通道原始状态</b>（见 {@code ChannelRawStatus}）。原因：
 * <ol>
 *   <li>归一化是有损的。「失败」在微信下可能是余额不足、限额、风控拦截，
 *       运营需要对着原始码定位，丢失后无法追溯。</li>
 *   <li>通道新增状态时，归一化映射可以先落到最接近的状态，
 *       原始值仍在，不会造成数据黑洞。</li>
 *   <li>对账与客服查询都需要原样展示通道侧状态。</li>
 * </ol>
 *
 * <p><b>状态迁移不写在本枚举里</b>，统一由 {@code PaymentStateMachine} 集中管理，
 * 便于一处审计「哪些转移是合法的」。
 */
public enum PaymentStatus {

    /** 已创建，尚未路由。 */
    CREATED("已创建"),

    /** 正在选择通道。瞬时状态，正常不应长时间停留。 */
    ROUTING("路由中"),

    /** 已下发通道，等待用户完成支付。 */
    PAYING("支付中"),

    /**
     * 用户支付中，等待输入密码/确认。
     *
     * <p>微信付款码支付特有：扣款需要用户在手机端确认，
     * 此时必须<b>主动轮询查单</b>而不能干等通知。这是「条码支付」类业务
     * 与普通扫码支付在实现上最大的区别。
     */
    USERPAYING("用户支付中"),

    /** 已授权，资金被冻结但尚未实际扣款（海外 auth 模式）。 */
    AUTHORIZED("已授权待请款"),

    /** 请款中。请款通常是异步的，需等通知或查单确认。 */
    CAPTURING("请款中"),

    /** 支付成功且资金已扣。非终态——后续仍可退款。 */
    SUCCEEDED("支付成功"),

    /** 支付失败。终态。 */
    FAILED("支付失败"),

    /** 已关闭（商户主动关单或超时未支付）。终态。 */
    CLOSED("已关闭"),

    /** 退款处理中。由退款上下文驱动。 */
    REFUNDING("退款中"),

    /** 部分退款成功。仍可继续退剩余金额。 */
    PARTIAL_REFUNDED("部分退款"),

    /** 全额退款完成。终态。 */
    REFUNDED("已全额退款"),
    ;

    private final String displayName;

    PaymentStatus(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /** 是否已实际收到资金（可进入退款流程）。 */
    public boolean isPaid() {
        return this == SUCCEEDED || this == PARTIAL_REFUNDED || this == REFUNDING;
    }

    /** 是否为终态：进入后不可再发生任何状态变化。 */
    public boolean isTerminal() {
        return this == FAILED || this == CLOSED || this == REFUNDED;
    }

    /** 是否已发生退款（部分或全额）。 */
    public boolean isRefunded() {
        return this == REFUNDING || this == PARTIAL_REFUNDED || this == REFUNDED;
    }

    /** 是否仍在通道侧处理中（需要查单或等通知）。 */
    public boolean isPending() {
        return this == PAYING || this == USERPAYING || this == AUTHORIZED
                || this == CAPTURING || this == ROUTING;
    }
}
