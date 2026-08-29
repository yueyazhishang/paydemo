package com.example.payment.infrastructure.gateway.jd;

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
 * 京东支付适配器（防腐层 Adapter）。
 *
 * <p>京东支付真实对接差异点：
 * <ul>
 *   <li><b>金额单位</b>：京东支付金额单位为「分」（long），与统一模型一致，无需换算。</li>
 *   <li><b>签名方式</b>：MD5/RSA 双签名体系——请求按参数名字典序拼串后 MD5 签名，
 *       回调通过 HTTP Header 的 jd-sign 携带签名，需用商户 RSA 私钥/京东公钥验签。</li>
 *   <li><b>回调格式与应答</b>：POST JSON 报文；应答需返回 JSON {@code {"code":"0000","desc":"成功"}}，
 *       否则京东支付按固定间隔重发通知。</li>
 *   <li><b>退款</b>：异步模式——退款申请受理（ACCEPTED）后等待退款结果通知确认终态。</li>
 *   <li><b>对账文件</b>：商户对账单下载接口（CSV，含支付/退款明细，按日生成）。</li>
 * </ul>
 */
@Slf4j
@Component
public class JdPayGateway implements PaymentGateway, BillDownloader {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Channel channel() {
        return Channel.JD_PAY;
    }

    @Override
    public GatewayPayResult prepay(GatewayPayRequest request) {
        // 京东支付金额单位为分，直接透传
        long amountFen = request.getAmount().getAmountMinor();
        log.info("[京东支付] 下单 /pay/getPayNum: orderId={}, amount={}分, currency={}, goodsName={}",
                request.getBizOrderNo(), amountFen, request.getAmount().getCurrency(), request.getSubject());
        String cashierUrl = "https://pay.jd.com/pay/index?orderId=" + request.getBizOrderNo()
                + "&amount=" + amountFen;
        return GatewayPayResult.ok(PayType.CASHIER_URL, cashierUrl, "jd_tx_" + request.getBizOrderNo());
    }

    @Override
    public GatewayQueryResult query(String paymentId) {
        log.info("[京东支付] 查单 /pay/queryOrder: orderId={}", paymentId);
        // paidAmount 置 null：真实实现应回传渠道侧实付金额（分）用于核对，防丢单边账
        return GatewayQueryResult.builder()
                .status(ChannelTradeStatus.SUCCESS)
                .channelTradeNo("MOCK_JD_" + tail(paymentId))
                .build();
    }

    @Override
    public ChannelCallbackMessage parseCallback(CallbackRequest callbackRequest) {
        String body = callbackRequest.getBody();
        // 模拟验签：京东支付回调需校验 Header 中的 jd-sign
        if (body == null || body.isBlank() || !callbackRequest.getHeaders().containsKey("jd-sign")) {
            throw new GatewayException("京东支付回调验签失败");
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            String orderId = root.path("orderId").asText();
            String jdTradeNo = root.path("jdTradeNo").asText();
            String status = root.path("status").asText();
            long amountFen = root.path("amount").asLong();
            log.info("[京东支付] 收到支付回调: orderId={}, jdTradeNo={}, status={}, amount={}分",
                    orderId, jdTradeNo, status, amountFen);
            // 真实实现应答 {"code":"0000","desc":"成功"}
            return ChannelCallbackMessage.builder()
                    .callbackType(CallbackType.PAYMENT)
                    .ourTradeNo(orderId)
                    .channelTradeNo(jdTradeNo)
                    .success("PAY_SUCCESS".equals(status))
                    .amountMinor(amountFen)
                    .signVerified(true)
                    .rawBody(body)
                    .build();
        } catch (GatewayException e) {
            throw e;
        } catch (Exception e) {
            throw new GatewayException("京东支付回调报文解析失败", e);
        }
    }

    @Override
    public GatewayRefundResult refund(GatewayRefundRequest request) {
        long refundFen = request.getRefundAmount().getAmountMinor();
        log.info("[京东支付] 申请退款 /pay/refund: orderId={}, jdTradeNo={}, refundAmount={}分, reason={}",
                request.getPaymentId(), request.getChannelTradeNo(), refundFen, request.getReason());
        // 京东支付退款为异步：受理成功后等待退款结果通知确认终态
        return GatewayRefundResult.accepted("MOCK_JD_REFUND_" + tail(request.getRefundId()));
    }

    @Override
    public List<BillRecord> download(LocalDate billDate) {
        // 真实实现：调用商户对账单下载接口获取 CSV（支付/退款明细），逐行解析为 BillRecord
        log.info("[京东支付] 下载对账单: bill_date={}", billDate.format(DateTimeFormatter.ISO_LOCAL_DATE));
        return List.of();
    }

    private String tail(String id) {
        if (id == null || id.isBlank()) {
            return "00000000";
        }
        return id.length() <= 8 ? id : id.substring(id.length() - 8);
    }
}
