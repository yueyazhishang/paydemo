package com.example.payment.infrastructure.gateway.applepay;

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
 * Apple Pay 适配器（防腐层 Adapter）。
 *
 * <p>Apple Pay 真实对接差异点：
 * <ul>
 *   <li><b>钱包能力定位</b>：Apple Pay 本身是「钱包能力」而非独立收单渠道——客户端弹出 PKPaymentSheet
 *       拿到加密的 PKPaymentToken 后，服务端须将 token 交给代收的 PSP（如 Stripe / Adyen / Antom）解密扣款。
 *       金额按处理方要求以最小货币单位（如分）传递。</li>
 *   <li><b>签名/认证</b>：token 内的 paymentProcessingCertificate 由 Apple 签发，PSP 侧解密；
 *       服务端与 PSP 之间用 API Key / Bearer 认证；PSP Webhook 验签由 PSP 方案决定。</li>
 *   <li><b>回调格式与应答</b>：本适配器的回调实际来自代收 PSP 的 Webhook（POST JSON），
 *       应答 HTTP 200 即确认。</li>
 *   <li><b>退款</b>：对 PSP 发起退款，同步返回 SUCCESS（处理方确认即终态，或由 PSP 通知确认）。</li>
 *   <li><b>对账文件</b>：对账基于 PSP 的对账文件/结算报告，而非 Apple；此处模拟返回。</li>
 * </ul>
 */
@Slf4j
@Component
public class ApplePayGateway implements PaymentGateway, BillDownloader {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Channel channel() {
        return Channel.APPLE_PAY;
    }

    @Override
    public GatewayPayResult prepay(GatewayPayRequest request) {
        // 金额按最小单位（分）传递给代收 PSP
        long amountMinor = request.getAmount().getAmountMinor();
        log.info("[Apple Pay] 收到 PKPaymentToken, 交由代收 PSP 处理: paymentId={}, amountMinor={}, currency={}",
                request.getBizOrderNo(), amountMinor, request.getAmount().getCurrency());
        // 模拟 PKPaymentToken JSON（真实场景由客户端 PKPaymentSheet 产出，含 encryptedPaymentData）
        String paymentTokenJson = "{\"version\":\"EC_v1\",\"data\":\"mock-encrypted-payment-data\","
                + "\"signature\":\"mock-signature\",\"header\":{\"ephemeralPublicKey\":\"mock-epk\","
                + "\"publicKeyHash\":\"mock-hash\",\"transactionId\":\"mock-txn-id\"}}";
        return GatewayPayResult.ok(PayType.JSAPI, paymentTokenJson, "apay_tx_" + request.getBizOrderNo());
    }

    @Override
    public GatewayQueryResult query(String paymentId) {
        log.info("[Apple Pay] 向代收 PSP 查单: paymentId={}", paymentId);
        // paidAmount 置 null：真实实现应回传 PSP 侧实付金额（分）用于核对
        return GatewayQueryResult.builder()
                .status(ChannelTradeStatus.SUCCESS)
                .channelTradeNo("MOCK_APAY_" + tail(paymentId))
                .build();
    }

    @Override
    public ChannelCallbackMessage parseCallback(CallbackRequest callbackRequest) {
        String body = callbackRequest.getBody();
        // 模拟验签：回调实际来自代收 PSP 的 Webhook
        if (body == null || body.isBlank()
                || !callbackRequest.getHeaders().containsKey("processor-signature")) {
            throw new GatewayException("Apple Pay 回调验签失败");
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            String paymentId = root.path("paymentId").asText();
            String processorTransactionId = root.path("processorTransactionId").asText();
            String status = root.path("status").asText();
            long amountMinor = root.path("amount").asLong();
            log.info("[Apple Pay] 收到 PSP Webhook: paymentId={}, processorTransactionId={}, status={}, amount={}分",
                    paymentId, processorTransactionId, status, amountMinor);
            return ChannelCallbackMessage.builder()
                    .callbackType(CallbackType.PAYMENT)
                    .ourTradeNo(paymentId)
                    .channelTradeNo(processorTransactionId)
                    .success("SUCCEEDED".equals(status))
                    .amountMinor(amountMinor)
                    .signVerified(true)
                    .rawBody(body)
                    .build();
        } catch (GatewayException e) {
            throw e;
        } catch (Exception e) {
            throw new GatewayException("Apple Pay 回调报文解析失败", e);
        }
    }

    @Override
    public GatewayRefundResult refund(GatewayRefundRequest request) {
        log.info("[Apple Pay] 向代收 PSP 发起退款: paymentId={}, processorTransactionId={}, refundAmount={}, reason={}",
                request.getPaymentId(), request.getChannelTradeNo(),
                request.getRefundAmount().getAmountMinor(), request.getReason());
        // PSP 退款同步返回即终态
        return GatewayRefundResult.success("MOCK_APAY_REFUND_" + tail(request.getRefundId()));
    }

    @Override
    public List<BillRecord> download(LocalDate billDate) {
        // 真实实现：解析代收 PSP 的对账文件/结算报告，逐条转换为 BillRecord
        log.info("[Apple Pay] 下载 PSP 对账文件: bill_date={}", billDate.format(DateTimeFormatter.ISO_LOCAL_DATE));
        return List.of();
    }

    private String tail(String id) {
        if (id == null || id.isBlank()) {
            return "00000000";
        }
        return id.length() <= 8 ? id : id.substring(id.length() - 8);
    }
}
