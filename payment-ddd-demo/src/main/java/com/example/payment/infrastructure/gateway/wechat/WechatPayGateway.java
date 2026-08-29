package com.example.payment.infrastructure.gateway.wechat;

import com.example.payment.domain.gateway.BillDownloader;
import com.example.payment.domain.gateway.BillRecord;
import com.example.payment.domain.gateway.CallbackRequest;
import com.example.payment.domain.gateway.ChannelCallbackMessage;
import com.example.payment.domain.gateway.CallbackType;
import com.example.payment.domain.gateway.ChannelTradeStatus;
import com.example.payment.domain.gateway.GatewayException;
import com.example.payment.domain.gateway.GatewayPayRequest;
import com.example.payment.domain.gateway.GatewayPayResult;
import com.example.payment.domain.gateway.GatewayQueryResult;
import com.example.payment.domain.gateway.GatewayRefundRequest;
import com.example.payment.domain.gateway.GatewayRefundResult;
import com.example.payment.domain.gateway.PayType;
import com.example.payment.domain.gateway.PaymentGateway;
import com.example.payment.domain.shared.Channel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 微信支付 V3 适配器（防腐层 Adapter）。
 *
 * <p>微信支付真实对接差异点：
 * <ul>
 *   <li><b>金额单位</b>：微信 V3 金额单位为「分」，long 传参，无需元字符串换算；
 *       回调中金额位于 {@code amount.total}（分）与 {@code amount.payer_total}（用户实付分）。</li>
 *   <li><b>签名方式</b>：HTTP Header 携带 Wechatpay-Signature / Wechatpay-Timestamp / Wechatpay-Nonce，
 *       用「微信平台证书 + SHA256withRSA」对报文体验签；API v3 key 做 AES-256-GCM 解密资源对象。</li>
 *   <li><b>回调格式与应答</b>：POST JSON，报文 resource 字段需解密后才是业务 JSON；
 *       应答须返回 {@code {"code":"SUCCESS"}}（HTTP 200/204），否则微信按衰减策略重试。</li>
 *   <li><b>退款</b>：异步模式——受理成功（ACCEPTED）后等待「退款结果通知」回调确认终态。</li>
 *   <li><b>对账文件</b>：申请交易账单 API（GZIP 压缩 CSV，分「所有订单/退款」两张表，T+1 可下载）。</li>
 * </ul>
 */
@Slf4j
@Component
public class WechatPayGateway implements PaymentGateway, BillDownloader {

    /** 微信商户号（mock 固定值，真实实现由配置注入） */
    private static final String MOCK_MCH_ID = "1900000001";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Channel channel() {
        return Channel.WECHAT_PAY;
    }

    @Override
    public GatewayPayResult prepay(GatewayPayRequest request) {
        // 微信 V3 金额单位为分：amountMinor 直接作为 long 透传，不做换算
        long totalFen = request.getAmount().getAmountMinor();
        log.info("[微信支付] V3 下单 POST /v3/pay/transactions/jsapi, mchid={}, out_trade_no={}, amount={{total:{},currency:{}}}, description={}",
                MOCK_MCH_ID, request.getBizOrderNo(), totalFen, request.getAmount().getCurrency(), request.getSubject());

        // buyerId 非空 → 用户已在微信小程序/公众号内 → JSAPI 支付（需 openid 换 prepay_id）
        if (request.getBuyerId() != null && !request.getBuyerId().isBlank()) {
            String jsapiJson = "{\"appId\":\"wx1234567890\",\"timeStamp\":\"1730000000\","
                    + "\"nonceStr\":\"mock-nonce\",\"package\":\"prepay_id=wx_mock_"
                    + request.getBizOrderNo() + "\",\"signType\":\"RSA\",\"paySign\":\"mock-sign\"}";
            return GatewayPayResult.ok(PayType.JSAPI, jsapiJson, "wx_tx_" + request.getBizOrderNo());
        }
        // 否则 → Native 扫码支付（返回二维码码串 code_url）
        String codeUrl = "weixin://wxpay/bizpayurl?pr=mock" + request.getBizOrderNo();
        return GatewayPayResult.ok(PayType.QR_CODE, codeUrl, "wx_tx_" + request.getBizOrderNo());
    }

    @Override
    public GatewayQueryResult query(String paymentId) {
        log.info("[微信支付] V3 查单 GET /v3/pay/transactions/out-trade-no/{}", paymentId);
        // paidAmount 置 null：mock 场景无法得知渠道侧实付金额，
        // 真实实现应回传渠道侧实付金额（amount.payer_total，分）用于核对，防丢单边账
        return GatewayQueryResult.builder()
                .status(ChannelTradeStatus.SUCCESS)
                .channelTradeNo("MOCK_WX_" + tail(paymentId))
                .build();
    }

    @Override
    public ChannelCallbackMessage parseCallback(CallbackRequest callbackRequest) {
        String body = callbackRequest.getBody();
        // 模拟验签：微信 V3 要求用平台证书对 Wechatpay-Signature 验签，此处仅校验头存在
        if (body == null || body.isBlank()
                || !callbackRequest.getHeaders().containsKey("wechatpay-signature")) {
            throw new GatewayException("微信回调验签失败");
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            String outTradeNo = root.path("outTradeNo").asText();
            String transactionId = root.path("transactionId").asText();
            String tradeState = root.path("tradeState").asText();
            long totalFen = root.path("amount").path("total").asLong();
            log.info("[微信支付] 收到支付回调: out_trade_no={}, transaction_id={}, trade_state={}, amount.total={}分",
                    outTradeNo, transactionId, tradeState, totalFen);
            // 真实实现应答 {"code":"SUCCESS"}，此处由上层 CallbackController 统一处理
            return ChannelCallbackMessage.builder()
                    .callbackType(CallbackType.PAYMENT)
                    .ourTradeNo(outTradeNo)
                    .channelTradeNo(transactionId)
                    .success("SUCCESS".equals(tradeState))
                    .amountMinor(totalFen)
                    .signVerified(true)
                    .rawBody(body)
                    .build();
        } catch (GatewayException e) {
            throw e;
        } catch (Exception e) {
            throw new GatewayException("微信回调报文解析失败", e);
        }
    }

    @Override
    public GatewayRefundResult refund(GatewayRefundRequest request) {
        // 微信退款金额单位同样为分
        long refundFen = request.getRefundAmount().getAmountMinor();
        log.info("[微信支付] V3 申请退款 POST /v3/refund/domestic/refunds, out_trade_no={}, transaction_id={}, amount={{refund:{}}}",
                request.getPaymentId(), request.getChannelTradeNo(), refundFen);
        // 微信退款为异步：受理成功后等待退款结果通知回调确认终态
        return GatewayRefundResult.accepted("MOCK_WX_REFUND_" + tail(request.getRefundId()));
    }

    @Override
    public List<BillRecord> download(LocalDate billDate) {
        // 真实实现：调用「申请交易账单」API 下载 GZIP 压缩的 CSV 对账单（按日 T+1），逐行解析为 BillRecord
        log.info("[微信支付] 下载对账单: bill_date={}", billDate.format(DateTimeFormatter.ISO_LOCAL_DATE));
        return List.of();
    }

    private String tail(String id) {
        if (id == null || id.isBlank()) {
            return "00000000";
        }
        return id.length() <= 8 ? id : id.substring(id.length() - 8);
    }
}
