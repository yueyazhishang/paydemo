package com.zxpay.domain.payment.port;

import com.zxpay.domain.payment.model.ChannelRequest;
import com.zxpay.domain.payment.model.ChannelResult;

/**
 * 出站端口：向通道发起支付。
 *
 * <p>领域层只说「我要向通道下单」，完全不知道底层是 HTTPS + 商户证书签名（微信）、
 * RSA2 签名（支付宝）、Bearer Key（Stripe）还是 OAuth2 + JSON（PayPal）。
 *
 * <p><b>实现契约（适配器必须遵守）：</b>
 * <ol>
 *   <li><b>幂等键必须使用传入的 {@code request.idempotencyKey()}</b>，严禁自行生成。
 *       重试时复用同一 key 是防重复扣款的唯一保障。</li>
 *   <li><b>不得吞掉超时</b>。网络超时必须转成
 *       {@code FailureInfo.unknown(...)} 返回，而不是抛异常或返回失败。
 *       超时意味着「结果未知」，直接判失败会造成「钱扣了订单失败」的严重事故。</li>
 *   <li><b>必须回填 {@code ChannelRawStatus}</b>，原样保留通道状态字符串。</li>
 *   <li><b>不得修改领域状态</b>。适配器只做翻译与传输，
 *       状态变更一律由 {@code PaymentOrder} 自己完成。</li>
 * </ol>
 */
public interface ChannelPaymentPort extends ChannelPort {

    ChannelResult pay(ChannelRequest request);
}
