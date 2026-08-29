package com.example.payment.infrastructure.gateway.antom;

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
 * Antom（支付宝国际）适配器（防腐层 Adapter）。
 *
 * <p>Antom 真实对接差异点：
 * <ul>
 *   <li><b>金额单位</b>：Antom 的 paymentAmount.value 按「币种最小单位」传值，
 *       且需同时上送 currency 字段——注意不同币种最小单位不同（如 JPY 的最小单位就是 1，无分），
 *       与国内渠道「一律分」的模型不同，依赖 Currency 枚举的 scale 语义。</li>
 *   <li><b>签名方式</b>：请求头携带 client-id + signature（Content-Signature 风格，
 *       对 method.path.body 拼串后用商户私钥 SHA256withRSA 签名）；回调用 Antom 公钥验签。</li>
 *   <li><b>回调格式与应答</b>：POST JSON（notifyCapture / notifyPayment 事件模型，
 *       result.resultStatus 取值 S/F/U）；验签通过后应答 JSON {@code {"result":{"resultStatus":"S","resultCode":"SUCCESS"}}}。</li>
 *   <li><b>退款</b>：异步模式——refund 接口受理后通过 refund result notification 通知终态（notifyCapture 风格）。</li>
 *   <li><b>对账文件</b>：结算/对账文件下载接口（按结算周期生成 CSV/Excel，多币种多结算主体拆分）。</li>
 * </ul>
 */
@Slf4j
@Component
public class AntomGateway implements PaymentGateway, BillDownloader {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Channel channel() {
        return Channel.ANTOM;
    }

    @Override
    public GatewayPayResult prepay(GatewayPayRequest request) {
        // Antom value 字段按币种最小单位：amountMinor 直接透传，同时上送币种
        long value = request.getAmount().getAmountMinor();
        log.info("[Antom] 创建收单 POST /ams/api/v1/payments/pay: paymentRequestId={}, paymentAmount={value:{}, currency:{}}, orderDescription={}",
                request.getBizOrderNo(), value, request.getAmount().getCurrency(), request.getSubject());
        String cashierUrl = "https://global.alipay.com/ams/api/v1/cashier?paymentRequestId="
                + request.getBizOrderNo() + "&value=" + value;
        return GatewayPayResult.ok(PayType.CASHIER_URL, cashierUrl, "antom_tx_" + request.getBizOrderNo());
    }

    @Override
    public GatewayQueryResult query(String paymentId) {
        log.info("[Antom] 查询 POST /ams/api/v1/payments/inquiryPayment: paymentRequestId={}", paymentId);
        // paidAmount 置 null：真实实现应回传渠道侧实付金额（value，按币种最小单位）用于核对
        return GatewayQueryResult.builder()
                .status(ChannelTradeStatus.SUCCESS)
                .channelTradeNo("MOCK_ANTOM_" + tail(paymentId))
                .build();
    }

    @Override
    public ChannelCallbackMessage parseCallback(CallbackRequest callbackRequest) {
        String body = callbackRequest.getBody();
        // 模拟验签：Antom 异步通知需用 Antom 公钥校验请求头 signature
        if (body == null || body.isBlank() || !callbackRequest.getHeaders().containsKey("signature")) {
            throw new GatewayException("Antom 回调验签失败");
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            String paymentRequestId = root.path("paymentRequestId").asText();
            String paymentId = root.path("paymentId").asText();
            String resultStatus = root.path("result").path("resultStatus").asText();
            long amountMinor = root.path("paymentAmount").path("value").asLong();
            log.info("[Antom] 收到 notifyCapture 通知: paymentRequestId={}, paymentId={}, result.resultStatus={}, paymentAmount.value={}",
                    paymentRequestId, paymentId, resultStatus, amountMinor);
            // 真实实现应答 {"result":{"resultStatus":"S","resultCode":"SUCCESS"}}
            return ChannelCallbackMessage.builder()
                    .callbackType(CallbackType.PAYMENT)
                    .ourTradeNo(paymentRequestId)
                    .channelTradeNo(paymentId)
                    .success("S".equals(resultStatus)) // S=成功, F=失败, U=处理中
                    .amountMinor(amountMinor)
                    .signVerified(true)
                    .rawBody(body)
                    .build();
        } catch (GatewayException e) {
            throw e;
        } catch (Exception e) {
            throw new GatewayException("Antom 回调报文解析失败", e);
        }
    }

    @Override
    public GatewayRefundResult refund(GatewayRefundRequest request) {
        long value = request.getRefundAmount().getAmountMinor();
        log.info("[Antom] 退款 POST /ams/api/v1/refunds/refund: refundRequestId={}, paymentId={}, refundAmount={value:{}}",
                request.getRefundId(), request.getChannelTradeNo(), value);
        // Antom 退款为异步：受理后通过 refund result notification（notifyCapture 风格）通知终态
        return GatewayRefundResult.accepted("MOCK_ANTOM_REFUND_" + tail(request.getRefundId()));
    }

    @Override
    public List<BillRecord> download(LocalDate billDate) {
        // 真实实现：调用对账文件下载接口（多币种多结算主体拆分），解析为 BillRecord
        log.info("[Antom] 下载对账文件: bill_date={}", billDate.format(DateTimeFormatter.ISO_LOCAL_DATE));
        return List.of();
    }

    private String tail(String id) {
        if (id == null || id.isBlank()) {
            return "00000000";
        }
        return id.length() <= 8 ? id : id.substring(id.length() - 8);
    }
}
