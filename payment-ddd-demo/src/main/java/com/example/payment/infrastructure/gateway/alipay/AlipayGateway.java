package com.example.payment.infrastructure.gateway.alipay;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 支付宝适配器（防腐层 Adapter）。
 *
 * <p>支付宝真实对接差异点：
 * <ul>
 *   <li><b>金额单位</b>：支付宝金额单位为「元字符串」（如 "99.99"），适配器需完成 分 ↔ 元字符串 双向换算。</li>
 *   <li><b>签名方式</b>：报文/表单字段 sign + sign_type，商户私钥 RSA2（SHA256withRSA）签名，
 *       回调验签使用支付宝公钥，且需先剔除 sign/sign_type 字段再拼串验签。</li>
 *   <li><b>回调格式与应答</b>：POST form（application/x-www-form-urlencoded），非 JSON；
 *       验签通过后必须原样输出纯文本 {@code success}，否则支付宝 24 小时内按衰减频率重发。</li>
 *   <li><b>退款</b>：同步模式——调用 alipay.trade.refund 同步返回 fund_change=Y / refund_fee 即终态成功。</li>
 *   <li><b>对账文件</b>：查询对账单下载地址接口（day 余额明细 CSV，编码 GBK，含签名校验）。</li>
 * </ul>
 */
@Slf4j
@Component
public class AlipayGateway implements PaymentGateway, BillDownloader {

    @Override
    public Channel channel() {
        return Channel.ALIPAY;
    }

    @Override
    public GatewayPayResult prepay(GatewayPayRequest request) {
        // 支付宝金额单位为元字符串：分 → 元（scale=2）
        long minor = request.getAmount().getAmountMinor();
        String totalAmount = BigDecimal.valueOf(minor, 2).toPlainString();
        log.info("[支付宝] 统一下单 alipay.trade.page.pay: out_trade_no={}, total_amount={}, subject={}",
                request.getBizOrderNo(), totalAmount, request.getSubject());
        String cashierUrl = "https://openapi.alipay.com/gateway.do?method=alipay.trade.page.pay"
                + "&out_trade_no=" + request.getBizOrderNo() + "&total_amount=" + totalAmount;
        return GatewayPayResult.ok(PayType.CASHIER_URL, cashierUrl, "alipay_tx_" + request.getBizOrderNo());
    }

    @Override
    public GatewayQueryResult query(String paymentId) {
        log.info("[支付宝] 查单 alipay.trade.query: out_trade_no={}", paymentId);
        // paidAmount 置 null：真实实现应回传渠道侧实付金额（trade_status=TRADE_SUCCESS 时的 total_amount，元字符串转分）用于核对
        return GatewayQueryResult.builder()
                .status(ChannelTradeStatus.SUCCESS)
                .channelTradeNo("MOCK_ALI_" + tail(paymentId))
                .build();
    }

    @Override
    public ChannelCallbackMessage parseCallback(CallbackRequest callbackRequest) {
        String body = callbackRequest.getBody();
        // 模拟验签：支付宝异步通知为 form 串，必须携带 sign 字段并用支付宝公钥 RSA2 验签
        if (body == null || body.isBlank() || !callbackRequest.getHeaders().containsKey("sign")) {
            throw new GatewayException("支付宝回调验签失败");
        }
        try {
            // 真实实现应使用标准 form 解析并做 URL 解码；此处按演示约定简单 split("&")（URL 解码可略）
            Map<String, String> params = new HashMap<>();
            for (String pair : body.split("&")) {
                int idx = pair.indexOf('=');
                if (idx > 0) {
                    params.put(pair.substring(0, idx), pair.substring(idx + 1));
                }
            }
            String outTradeNo = params.get("out_trade_no");
            String tradeNo = params.get("trade_no");
            String tradeStatus = params.get("trade_status");
            // total_amount 为元字符串（如 "99.99"），转回分：右移两位取整
            long amountMinor = new BigDecimal(params.getOrDefault("total_amount", "0"))
                    .movePointRight(2).longValueExact();
            log.info("[支付宝] 收到异步通知: out_trade_no={}, trade_no={}, trade_status={}, total_amount={}元",
                    outTradeNo, tradeNo, tradeStatus, params.get("total_amount"));
            // 真实实现验签通过后应答纯文本 "success"
            return ChannelCallbackMessage.builder()
                    .callbackType(CallbackType.PAYMENT)
                    .ourTradeNo(outTradeNo)
                    .channelTradeNo(tradeNo)
                    .success("TRADE_SUCCESS".equals(tradeStatus))
                    .amountMinor(amountMinor)
                    .signVerified(true)
                    .rawBody(body)
                    .build();
        } catch (GatewayException e) {
            throw e;
        } catch (Exception e) {
            throw new GatewayException("支付宝回调报文解析失败", e);
        }
    }

    @Override
    public GatewayRefundResult refund(GatewayRefundRequest request) {
        // 退款金额 分 → 元字符串
        String refundAmount = BigDecimal.valueOf(request.getRefundAmount().getAmountMinor(), 2).toPlainString();
        log.info("[支付宝] 退款 alipay.trade.refund: out_trade_no={}, trade_no={}, refund_amount={}",
                request.getPaymentId(), request.getChannelTradeNo(), refundAmount);
        // 支付宝退款为同步返回：接口响应即终态（SUCCESS）
        return GatewayRefundResult.success("MOCK_ALI_REFUND_" + tail(request.getRefundId()));
    }

    @Override
    public List<BillRecord> download(LocalDate billDate) {
        // 真实实现：调用 alipay.data.dataservice.bill.downloadurl.query 获取对账单 zip（GBK 编码 CSV），逐行解析为 BillRecord
        log.info("[支付宝] 下载对账单: bill_date={}", billDate.format(DateTimeFormatter.ISO_LOCAL_DATE));
        return List.of();
    }

    private String tail(String id) {
        if (id == null || id.isBlank()) {
            return "00000000";
        }
        return id.length() <= 8 ? id : id.substring(id.length() - 8);
    }
}
