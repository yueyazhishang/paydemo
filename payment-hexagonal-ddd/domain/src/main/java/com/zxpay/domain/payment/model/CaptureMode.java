package com.zxpay.domain.payment.model;

/**
 * 请款模式：<b>国内外支付语义分歧的核心</b>。
 *
 * <p>国内第三方支付默认是 <b>Sale</b>——用户付款即扣款，一步到位。
 * 因此国内几乎没有「授权」这个概念，商户代码里也就不存在请款这一步。
 *
 * <p>海外卡组织（Visa/Mastercard）的标准模型是<b>两段式</b>：
 * <ol>
 *   <li><b>Authorization（授权）</b>：冻结用户额度。此时钱没走，但用户可用额度被占。</li>
 *   <li><b>Capture（请款）</b>：商户确认可履约（有货、无风险）后，真正把钱划走。</li>
 * </ol>
 *
 * <p>这带来几个国内开发者容易踩的坑：
 * <ul>
 *   <li><b>授权会过期</b>：卡组织通常 7 天（部分场景 30 天），超期未请款自动释放，
 *       再请款必然失败。酒店预授权是最典型的踩坑场景。</li>
 *   <li><b>可部分请款</b>：授权 100 美元，实际只发了 80 美元的货，
 *       请款 80 即可，剩余 20 自动解冻。国内想实现同样效果只能「全额支付 + 部分退款」，
 *       用户体验和资金占用完全不同。</li>
 *   <li><b>请款金额不可超过授权额</b>（部分通道允许 115% 的小幅上浮用于小费/加油）。</li>
 *   <li><b>未请款的授权要用 VOID 撤销</b>，不能用 REFUND——钱根本没扣，退不了。</li>
 * </ul>
 *
 * <p>因此领域模型里必须显式保留这个维度，不能简单假设「支付成功 = 钱到账」。
 */
public enum CaptureMode {

    /** 自动请款：授权与请款一次完成，或授权后立即自动请款。国内默认。 */
    AUTOMATIC("自动请款"),

    /** 手动请款：仅授权，等商户显式调用请款接口。海外卡支付与预售业务常用。 */
    MANUAL("手动请款"),
    ;

    private final String displayName;

    CaptureMode(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /** 是否需要商户显式发起请款。 */
    public boolean requiresExplicitCapture() {
        return this == MANUAL;
    }
}
