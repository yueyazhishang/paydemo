package com.example.payment.infrastructure.gateway.paypal;

import com.example.payment.domain.gateway.BillDownloader;
import com.example.payment.domain.gateway.BillRecord;
import com.example.payment.domain.gateway.CallbackRequest;
import com.example.payment.domain.gateway.CallbackType;
import com.example.payment.domain.gateway.ChannelCallbackMessage;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * PayPal 适配器（防腐层 Adapter）。
 *
 * <p>PayPal 真实对接差异点：
 * <ul>
 *   <li><b>金额单位</b>：PayPal REST v2 金额为主单位字符串（如 "12.34"，amount.value），
 *       适配器需完成 分 ↔ 元字符串 双向换算，且币种支持 USD/EUR/GBP 等（不支持 CNY 收单）。</li>
 *   <li><b>签名/认证</b>：OAuth2 Client Credentials 流程获取 Access Token，所有请求携带
 *       {@code Authorization: Bearer <access_token>}；Webhook 通过 PayPal 签名证书
 *       对 transmission_id/time/cert_url/auth_algo/signature 做验签（Verify Webhook Signature API）。</li>
 *   <li><b>回调格式与应答</b>：Webhook 为 POST JSON（事件模型，如 CHECKOUT.ORDER.APPROVED /
 *       PAYMENT.CAPTURE.COMPLETED），事件体包在 resource 字段内；应答 HTTP 200 即确认，无特殊报文格式。</li>
 *   <li><b>退款</b>：同步模式——对 capture 调用 refund 接口，同步返回 COMPLETED 即终态成功。</li>
 *   <li><b>对账文件</b>：无文件下载，通过 Transaction Search API（list-transactions）按时间段拉取结算明细。</li>
 * </ul>
 */
@Slf4j
@Component
public class PaypalGateway implements PaymentGateway, BillDownloader {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Channel channel() {
        return Channel.PAYPAL;
    }

    @Override
    public GatewayPayResult prepay(GatewayPayRequest request) {
        // PayPal 金额为主单位字符串：分 → 元
        String amountValue = BigDecimal.valueOf(request.getAmount().getAmountMinor(),
                request.getAmount().getCurrency().getScale()).toPlainString();
        log.info("[PayPal] 创建订单 POST /v2/checkout/orders, intent=CAPTURE, purchase_units=[{amount={currency_code:{}, value:{}}, custom_id:{}}]",
                request.getAmount().getCurrency(), amountValue, request.getBizOrderNo());
        // 真实实现此处需先 POST /v1/oauth2/token 换取 Bearer Access Token
        String redirectUrl = "https://www.sandbox.paypal.com/checkoutnow?token=mockToken_"
                + request.getBizOrderNo();
        return GatewayPayResult.ok(PayType.REDIRECT, redirectUrl, "paypal_tx_" + request.getBizOrderNo());
    }

    @Override
    public GatewayQueryResult query(String paymentId) {
        log.info("[PayPal] 查单 GET /v2/checkout/orders/{}", paymentId);
        // paidAmount 置 null：真实实现应回传渠道侧实付金额（amount.value，元字符串转分）用于核对
        return GatewayQueryResult.builder()
                .status(ChannelTradeStatus.SUCCESS)
                .channelTradeNo("MOCK_PP_" + tail(paymentId))
                .build();
    }

    @Override
    public ChannelCallbackMessage parseCallback(CallbackRequest callbackRequest) {
        String body = callbackRequest.getBody();
        // 模拟认证：PayPal Webhook 请求头携带 Authorization: Bearer <access_token>（OAuth2 Client Credentials）
        if (body == null || body.isBlank()
                || !callbackRequest.getHeaders().containsKey("paypal-transmission-signature")) {
            throw new GatewayException("PayPal 回调验签失败");
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            // 真实 PayPal Webhook 事件体在 resource.supplementary_data.related_ids.order_id，
            // 此处按演示约定简化为顶层 orderNo 字段
            String orderNo = root.path("orderNo").asText();
            String captureId = root.path("captureId").asText();
            String status = root.path("status").asText();
            // amount 为主单位字符串（如 "12.34"），转回分：右移两位取整
            long amountMinor = new BigDecimal(root.path("amount").asText("0"))
                    .movePointRight(2).longValueExact();
            log.info("[PayPal] 收到 Webhook: orderNo={}, captureId={}, status={}, amount={}主单位",
                    orderNo, captureId, status, root.path("amount").asText());
            // 真实实现应答 HTTP 200 即确认，无特殊报文
            return ChannelCallbackMessage.builder()
                    .callbackType(CallbackType.PAYMENT)
                    .ourTradeNo(orderNo)
                    .channelTradeNo(captureId)
                    .success("COMPLETED".equals(status))
                    .amountMinor(amountMinor)
                    .signVerified(true)
                    .rawBody(body)
                    .build();
        } catch (GatewayException e) {
            throw e;
        } catch (Exception e) {
            throw new GatewayException("PayPal 回调报文解析失败", e);
        }
    }

    @Override
    public GatewayRefundResult refund(GatewayRefundRequest request) {
        // 退款金额 分 → 主单位字符串
        String refundValue = BigDecimal.valueOf(request.getRefundAmount().getAmountMinor(),
                request.getRefundAmount().getCurrency().getScale()).toPlainString();
        log.info("[PayPal] 退款 POST /v2/payments/captures/{}/refunds, amount={value:{}}",
                request.getChannelTradeNo(), refundValue);
        // PayPal 退款为同步返回：响应 COMPLETED 即终态成功
        return GatewayRefundResult.success("MOCK_PP_REFUND_" + tail(request.getRefundId()));
    }

    @Override
    public List<BillRecord> download(LocalDate billDate) {
        // 真实实现：调用 Transaction Search API（GET /v1/reporting/transactions）按日拉取结算明细并解析为 BillRecord
        log.info("[PayPal] 拉取交易明细: bill_date={}", billDate.format(DateTimeFormatter.ISO_LOCAL_DATE));
        return List.of();
    }

    private String tail(String id) {
        if (id == null || id.isBlank()) {
            return "00000000";
        }
        return id.length() <= 8 ? id : id.substring(id.length() - 8);
    }
}
