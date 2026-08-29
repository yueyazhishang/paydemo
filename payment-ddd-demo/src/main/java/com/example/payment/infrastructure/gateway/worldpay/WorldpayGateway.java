package com.example.payment.infrastructure.gateway.worldpay;

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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Worldpay 适配器（防腐层 Adapter）。
 *
 * <p>Worldpay 真实对接差异点：
 * <ul>
 *   <li><b>金额单位</b>：Worldpay 报文（XML / 新版 JSON）金额为「主单位小数」（如 &lt;amount value="12.34"
 *       exponent="2"/&gt; 或 {"value":"12.34"}），适配器需完成 分 ↔ 元 双向换算。</li>
 *   <li><b>签名方式</b>：基础认证（商户码 + XML 密码）或 OAuth2 Client Credentials（新版 RAFT 接口）；
 *       通知（Notification）本身不带签名头，需结合主动查单兜底确认。</li>
 *   <li><b>回调格式与应答</b>：Worldpay 以 XML Notification（&lt;notify&gt;）或新版本 Webhook JSON
 *       推送 lastEvent（如 AUTHORISED / CAPTURED / REFUNDED）；应答 HTTP 200 即确认，无特殊报文。</li>
 *   <li><b>退款</b>：同步模式——修改订单（&lt;modify&gt; + &lt;orderModification&gt; REFUND）后同步返回 OK。</li>
 *   <li><b>对账文件</b>：结算报告（Settlement Files / SFTP，SIT 报表，含 captured/refunded/chargeback 明细）。</li>
 * </ul>
 */
@Slf4j
@Component
public class WorldpayGateway implements PaymentGateway, BillDownloader {

    /** 从回调报文中宽松抽取 orderCode（最多 32 位字母数字） */
    private static final Pattern ORDER_CODE_PATTERN = Pattern.compile("orderCode[\"'=:\\s]+([A-Za-z0-9]{1,32})");

    @Override
    public Channel channel() {
        return Channel.WORLDPAY;
    }

    @Override
    public GatewayPayResult prepay(GatewayPayRequest request) {
        // Worldpay 报文金额为主单位小数：分 → 元
        String majorAmount = BigDecimal.valueOf(request.getAmount().getAmountMinor(),
                request.getAmount().getCurrency().getScale()).toPlainString();
        log.info("[Worldpay] 下单 POST /merchant/{}/orders: orderCode={}, amount={value:{}, currency:{}}",
                "MOCK_MERCHANT_CODE", request.getBizOrderNo(), majorAmount, request.getAmount().getCurrency());
        String redirectUrl = "https://payments.worldpay.com/app/hpp/integration/wpg/corporate?orderKey="
                + request.getBizOrderNo() + "&amount=" + majorAmount;
        return GatewayPayResult.ok(PayType.REDIRECT, redirectUrl, "wp_tx_" + request.getBizOrderNo());
    }

    @Override
    public GatewayQueryResult query(String paymentId) {
        log.info("[Worldpay] 查单 GET /merchant/{}/orders/{}", "MOCK_MERCHANT_CODE", paymentId);
        // paidAmount 置 null：真实实现应回传渠道侧实付金额（主单位小数转分）用于核对
        return GatewayQueryResult.builder()
                .status(ChannelTradeStatus.SUCCESS)
                .channelTradeNo("MOCK_WP_" + tail(paymentId))
                .build();
    }

    @Override
    public ChannelCallbackMessage parseCallback(CallbackRequest callbackRequest) {
        String body = callbackRequest.getBody();
        if (body == null || body.isBlank()) {
            throw new GatewayException("Worldpay 回调验签失败");
        }
        // 成功判定：兼容新版 JSON（"lastEvent":"AUTHORISED"）与旧版 XML（<lastEvent>AUTHORISED</lastEvent>）
        boolean authorised = body.contains("\"lastEvent\":\"AUTHORISED\"")
                || body.contains("<lastEvent>AUTHORISED</lastEvent>");
        // ourTradeNo 宽松抽取：从 orderCode 之后的字母数字段提取（真实实现需完整解析 XML/JSON 报文）
        String ourTradeNo = "";
        Matcher matcher = ORDER_CODE_PATTERN.matcher(body);
        if (matcher.find()) {
            ourTradeNo = matcher.group(1);
        }
        // 真实实现需解析 XML 中的 amount/exponent 换算金额，并完整解析 <notify> 结构；
        // 此处为 mock 演示，找不到 orderCode 时按 NOT_FOUND 语义处理（ourTradeNo 置空）
        log.info("[Worldpay] 收到 Notification: orderCode={}, lastEvent=AUTHORISED?{}", ourTradeNo, authorised);
        return ChannelCallbackMessage.builder()
                .callbackType(CallbackType.PAYMENT)
                .ourTradeNo(ourTradeNo)
                .channelTradeNo("wp_evt_" + Integer.toHexString(body.hashCode()))
                .success(authorised)
                .amountMinor(null)
                .signVerified(true)
                .rawBody(body)
                .build();
    }

    @Override
    public GatewayRefundResult refund(GatewayRefundRequest request) {
        // 退款金额 分 → 主单位小数
        String majorAmount = BigDecimal.valueOf(request.getRefundAmount().getAmountMinor(),
                request.getRefundAmount().getCurrency().getScale()).toPlainString();
        log.info("[Worldpay] 退款 POST /merchant/{}/orders/{}/modifications: type=REFUND, value={}",
                "MOCK_MERCHANT_CODE", request.getChannelTradeNo(), majorAmount);
        // Worldpay 退款为同步返回：响应 OK 即受理并按同步语义确认
        return GatewayRefundResult.success("MOCK_WP_REFUND_" + tail(request.getRefundId()));
    }

    @Override
    public List<BillRecord> download(LocalDate billDate) {
        // 真实实现：从 SFTP 拉取 Worldpay 结算文件（SIT 报表，captured/refunded/chargeback 明细），解析为 BillRecord
        log.info("[Worldpay] 下载结算文件: bill_date={}", billDate.format(DateTimeFormatter.ISO_LOCAL_DATE));
        return List.of();
    }

    private String tail(String id) {
        if (id == null || id.isBlank()) {
            return "00000000";
        }
        return id.length() <= 8 ? id : id.substring(id.length() - 8);
    }
}
