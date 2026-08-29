package com.example.payment.infrastructure.gateway.stripe;

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

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Stripe 适配器（防腐层 Adapter）。
 *
 * <p>Stripe 真实对接差异点：
 * <ul>
 *   <li><b>金额单位</b>：Stripe 金额单位为「分」（API 中 amount 为 int 类型最小货币单位），
 *       且 JPY 等零小数币种的单位为 1——依赖 Currency 的 scale 语义，与统一模型一致。</li>
 *   <li><b>签名方式</b>：Bearer API Key（ sk_live_ / sk_test_ ）认证；
 *       Webhook 通过 Header Stripe-Signature（t=时间戳,v1=HMAC-SHA256 签名）验证，
 *       签名密钥为 Webhook endpoint 的 whsec_ secret，须校验时间戳容差（默认 5 分钟）防重放。</li>
 *   <li><b>回调格式与应答</b>：POST JSON 事件模型（type + data.object，如
 *       checkout.session.completed / payment_intent.succeeded / charge.refunded）；
 *       应答 HTTP 2xx 即确认，否则 Stripe 按指数退避重试 3 天。</li>
 *   <li><b>退款</b>：同步模式——POST /v1/refunds 同步返回 ChargeRefund 对象，status=succeeded 即终态成功。</li>
 *   <li><b>对账文件</b>：Balance Report / Payout Reconciliation API（生成 CSV 文件，按日/按 payout 维度）。</li>
 * </ul>
 */
@Slf4j
@Component
public class StripeGateway implements PaymentGateway, BillDownloader {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Channel channel() {
        return Channel.STRIPE;
    }

    @Override
    public GatewayPayResult prepay(GatewayPayRequest request) {
        // Stripe 金额单位为分（int），直接透传
        int amountInt = Math.toIntExact(request.getAmount().getAmountMinor());
        log.info("[Stripe] 创建 Checkout Session POST /v1/checkout/sessions: client_reference_id={}, amount_total={}分, currency={}, metadata.paymentId={}",
                request.getBizOrderNo(), amountInt, request.getAmount().getCurrency().name(), request.getBizOrderNo());
        String checkoutUrl = "https://checkout.stripe.com/c/pay/cs_mock_" + request.getBizOrderNo()
                + "?amount=" + amountInt;
        return GatewayPayResult.ok(PayType.CASHIER_URL, checkoutUrl, "cs_mock_" + request.getBizOrderNo());
    }

    @Override
    public GatewayQueryResult query(String paymentId) {
        log.info("[Stripe] 查单 GET /v1/checkout/sessions?client_reference_id={}", paymentId);
        // paidAmount 置 null：真实实现应回传渠道侧实付金额（amount_total，分）用于核对
        return GatewayQueryResult.builder()
                .status(ChannelTradeStatus.SUCCESS)
                .channelTradeNo("MOCK_STRIPE_" + tail(paymentId))
                .build();
    }

    @Override
    public ChannelCallbackMessage parseCallback(CallbackRequest callbackRequest) {
        String body = callbackRequest.getBody();
        // 模拟验签：Stripe Webhook 必须携带 Stripe-Signature 头（t=时间戳,v1=HMAC-SHA256）
        if (body == null || body.isBlank()
                || !callbackRequest.getHeaders().containsKey("stripe-signature")) {
            throw new GatewayException("Stripe 回调验签失败");
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            String type = root.path("type").asText();
            JsonNode dataObject = root.path("data").path("object");
            String ourTradeNo = dataObject.path("metadata").path("paymentId").asText();
            long amountTotal = dataObject.path("amount_total").asLong();
            boolean success = "checkout.session.completed".equals(type)
                    || "payment_intent.succeeded".equals(type);
            log.info("[Stripe] 收到 Webhook: type={}, data.object.metadata.paymentId={}, amount_total={}分",
                    type, ourTradeNo, amountTotal);
            // 真实实现应答 HTTP 2xx 即确认
            return ChannelCallbackMessage.builder()
                    .callbackType(CallbackType.PAYMENT)
                    .ourTradeNo(ourTradeNo)
                    .channelTradeNo(dataObject.path("id").asText())
                    .success(success)
                    .amountMinor(amountTotal)
                    .signVerified(true)
                    .rawBody(body)
                    .build();
        } catch (GatewayException e) {
            throw e;
        } catch (Exception e) {
            throw new GatewayException("Stripe 回调报文解析失败", e);
        }
    }

    @Override
    public GatewayRefundResult refund(GatewayRefundRequest request) {
        // Stripe 退款金额单位为分（int）
        int refundAmountInt = Math.toIntExact(request.getRefundAmount().getAmountMinor());
        log.info("[Stripe] 退款 POST /v1/refunds: payment_intent={}, amount={}分, reason={}",
                request.getChannelTradeNo(), refundAmountInt, request.getReason());
        // Stripe 退款为同步返回：status=succeeded 即终态成功
        return GatewayRefundResult.success("MOCK_STRIPE_REFUND_" + tail(request.getRefundId()));
    }

    @Override
    public List<BillRecord> download(LocalDate billDate) {
        // 真实实现：调用 Balance Report API 生成并下载 CSV 对账文件（按日/按 payout），解析为 BillRecord
        log.info("[Stripe] 下载对账文件: bill_date={}", billDate.format(DateTimeFormatter.ISO_LOCAL_DATE));
        return List.of();
    }

    private String tail(String id) {
        if (id == null || id.isBlank()) {
            return "00000000";
        }
        return id.length() <= 8 ? id : id.substring(id.length() - 8);
    }
}
