#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成 channel SPI 数据契约 + 路由"""
import os

BASE = "/Users/abc/WorkBuddy/2026-08-29-13-23-42/payment-ddd-demo"
F = {}
P = "payment-domain/src/main/java/com/demo/payment/domain/channel/"

F[P + "model/ChannelResultStatus.java"] = r'''
package com.demo.payment.domain.channel.model;

/**
 * 通道返回的业务结果状态。
 *
 * <p><b>为什么必须有 UNKNOWN 这一态？</b>
 * 这是支付系统一致性设计的<b>分水岭</b>。
 *
 * <p>调用通道时网络超时，你不知道请求到底有没有被通道处理。
 * 此时如果武断地判定为"失败"并关闭订单，实际通道可能已经扣款成功 ——
 * 用户付了钱，商户没收到单，这就是<b>掉单</b>。
 * 反过来如果判定为"成功"，用户实际没付款，商户就发货了 —— 这是<b>资损</b>。
 *
 * <p>正确做法：超时一律返回 {@code UNKNOWN}，订单保持"支付中"，
 * 然后<b>以主动查证为准</b>定终态。任何把网络超时直接映射成"失败"的代码，
 * 都是一个潜在的掉单 bug。
 */
public enum ChannelResultStatus {

    /** 已受理，等待用户完成支付（微信拿到 prepay_id、Stripe 拿到 client_secret） */
    PENDING,

    /** 通道明确返回成功 */
    SUCCEEDED,

    /** 通道明确返回失败（余额不足、风控拦截、卡被拒等） */
    FAILED,

    /**
     * 结果未知 —— 网络超时、响应无法解析、通道返回 5xx。
     * <b>必须通过主动查证确认，绝不能当作失败处理。</b>
     */
    UNKNOWN,

    /** 已授权但未请款（两段式通道特有） */
    AUTHORIZED,
    ;

    public boolean isFinal() {
        return this == SUCCEEDED || this == FAILED || this == AUTHORIZED;
    }
}
'''

F[P + "spi/PayCommand.java"] = r'''
package com.demo.payment.domain.channel.spi;

import com.demo.payment.domain.acquiring.model.vo.OutTradeNo;
import com.demo.payment.domain.channel.model.PaymentMethodType;
import com.demo.payment.shared.money.Money;

import java.util.HashMap;
import java.util.Map;

/**
 * 发起支付的命令对象。
 *
 * <p><b>关于 {@code extraParams}：</b>
 * 这是"统一抽象"与"通道特殊性"之间妥协的产物。
 * 理想情况是所有参数都进强类型字段，但现实是：
 * 微信 JSAPI 必须要 openid，Stripe 必须要 payment_method，Antom 的 APM 各有各的必填项。
 * 若把这些都提升为统一字段，接口会迅速腐化成"所有通道参数的并集"，
 * 每个通道只用其中 3 个，其余 20 个都是噪音。
 *
 * <p>因此保留一个逃生舱 {@code extraParams}，但<b>严格约束其使用</b>：
 * 只允许放通道特有的非核心参数，核心业务字段（金额、订单号、币种）必须在强类型字段上。
 */
public record PayCommand(
        OutTradeNo outTradeNo,
        Money amount,
        PaymentMethodType paymentMethod,
        String subject,
        String notifyUrl,
        String returnUrl,
        String clientIp,

        /**
         * 付款人在通道侧的身份标识。
         * 微信 JSAPI → openid；Stripe → customer_id；支付宝 → buyer_id
         */
        String payerId,

        /**
         * 支付凭证（支付方式为凭证网络类时使用）。
         * Apple Pay → PKPaymentToken 的 paymentData；Stripe → payment_method_id
         */
        String paymentCredential,

        /** 幂等键，由上层按通道能力决定如何传递 */
        String idempotencyKey,

        /** 订单过期时间（秒），部分通道支持（微信 time_expire、支付宝 timeout_express） */
        Integer expireSeconds,

        /** 国家或地区码（ISO 3166-1 alpha-2），海外通道必填 */
        String countryCode,

        /** 语言（Antom、PayPal 的收银台本地化） */
        String locale,

        /** 通道特有参数 */
        Map<String, String> extraParams
) {
    public PayCommand {
        if (extraParams == null) {
            extraParams = new HashMap<>();
        }
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private OutTradeNo outTradeNo;
        private Money amount;
        private PaymentMethodType paymentMethod;
        private String subject;
        private String notifyUrl;
        private String returnUrl;
        private String clientIp;
        private String payerId;
        private String paymentCredential;
        private String idempotencyKey;
        private Integer expireSeconds;
        private String countryCode;
        private String locale;
        private final Map<String, String> extraParams = new HashMap<>();

        public Builder outTradeNo(OutTradeNo v) { this.outTradeNo = v; return this; }
        public Builder amount(Money v) { this.amount = v; return this; }
        public Builder paymentMethod(PaymentMethodType v) { this.paymentMethod = v; return this; }
        public Builder subject(String v) { this.subject = v; return this; }
        public Builder notifyUrl(String v) { this.notifyUrl = v; return this; }
        public Builder returnUrl(String v) { this.returnUrl = v; return this; }
        public Builder clientIp(String v) { this.clientIp = v; return this; }
        public Builder payerId(String v) { this.payerId = v; return this; }
        public Builder paymentCredential(String v) { this.paymentCredential = v; return this; }
        public Builder idempotencyKey(String v) { this.idempotencyKey = v; return this; }
        public Builder expireSeconds(Integer v) { this.expireSeconds = v; return this; }
        public Builder countryCode(String v) { this.countryCode = v; return this; }
        public Builder locale(String v) { this.locale = v; return this; }
        public Builder extra(String k, String v) { this.extraParams.put(k, v); return this; }

        public PayCommand build() {
            return new PayCommand(outTradeNo, amount, paymentMethod, subject, notifyUrl,
                    returnUrl, clientIp, payerId, paymentCredential, idempotencyKey,
                    expireSeconds, countryCode, locale, extraParams);
        }
    }
}
'''

F[P + "spi/PayResponse.java"] = r'''
package com.demo.payment.domain.channel.spi;

import com.demo.payment.domain.acquiring.model.vo.OutTradeNo;
import com.demo.payment.domain.channel.model.ChannelResultStatus;

import java.util.HashMap;
import java.util.Map;

/**
 * 支付受理响应。
 *
 * <p><b>最容易误解的一点：{@code status == PENDING} 不代表失败。</b>
 * 国内通道下单后返回的是"支付凭证"（prepay_id / code_url），
 * 用户还要在 App 里完成付款。此时订单处于 PENDING 才是正常状态。
 * 很多新手看到没返回"成功"就判定失败并关单，结果用户正在输入密码时订单被关掉了。
 *
 * <p>各通道的凭证形态差异极大，全部收敛到 {@code credential} 这一组 Map 里：
 * <pre>
 *   微信 JSAPI  →  prepayId, nonceStr, timestamp, paySign, package
 *   微信 Native →  codeUrl
 *   支付宝 APP  →  orderString（可直接唤起 App 的串）
 *   Stripe      →  clientSecret（前端 confirm 用）
 *   PayPal      →  approvalUrl（跳转链接） + orderId
 *   Antom       →  paymentSessionData / redirectUrl / normalUrl
 *   Worldpay    →  orderCode + mac（跳转）
 * </pre>
 */
public record PayResponse(
        OutTradeNo outTradeNo,
        ChannelResultStatus status,
        String channelTransactionId,
        String channelRawStatus,
        String code,
        String message,

        /**
         * 支付凭证，用于前端拉起支付。键的含义见各通道适配器文档。
         */
        Map<String, String> credential,

        /** 是否为通道基础设施故障（true 表示可重试/切通道） */
        boolean infrastructureError
) {
    public PayResponse {
        if (credential == null) {
            credential = new HashMap<>();
        }
    }

    public static PayResponse pending(OutTradeNo outTradeNo, Map<String, String> credential) {
        return new PayResponse(outTradeNo, ChannelResultStatus.PENDING, null, null,
                null, null, credential, false);
    }

    public static PayResponse succeeded(OutTradeNo outTradeNo, String channelTxId) {
        return new PayResponse(outTradeNo, ChannelResultStatus.SUCCEEDED, channelTxId,
                "SUCCESS", null, null, Map.of(), false);
    }

    public static PayResponse failed(OutTradeNo outTradeNo, String code, String message) {
        return new PayResponse(outTradeNo, ChannelResultStatus.FAILED, null, "FAILED",
                code, message, Map.of(), false);
    }

    /**
     * 结果未知 —— 网络超时等场景。
     * <b>返回此值时，上层必须保持订单为"支付中"并发起查证，绝不能关单。</b>
     */
    public static PayResponse unknown(OutTradeNo outTradeNo, String message) {
        return new PayResponse(outTradeNo, ChannelResultStatus.UNKNOWN, null, null,
                "UNKNOWN", message, Map.of(), true);
    }

    public static PayResponse infraError(OutTradeNo outTradeNo, String message) {
        return new PayResponse(outTradeNo, ChannelResultStatus.UNKNOWN, null, null,
                "INFRA_ERROR", message, Map.of(), true);
    }

    public boolean isPending() { return status == ChannelResultStatus.PENDING; }
    public boolean isSucceeded() { return status == ChannelResultStatus.SUCCEEDED; }
    public boolean isUnknown() { return status == ChannelResultStatus.UNKNOWN; }
}
'''

F[P + "spi/QueryCommand.java"] = r'''
package com.demo.payment.domain.channel.spi;

import com.demo.payment.domain.acquiring.model.vo.OutTradeNo;

/**
 * 查证命令。
 *
 * <p><b>为什么查证接口要用 outTradeNo 而非 channelTransactionId？</b>
 * 因为下单超时的场景下，我们根本没拿到 channelTransactionId。
 * 查证必须支持"只用我方订单号查"，否则超时场景无法闭环 ——
 * 这正是 UNKNOWN 状态必须由查证兜底的原因。
 */
public record QueryCommand(OutTradeNo outTradeNo, String channelTransactionId) {

    public static QueryCommand byOutTradeNo(OutTradeNo outTradeNo) {
        return new QueryCommand(outTradeNo, null);
    }
}
'''

F[P + "spi/QueryResponse.java"] = r'''
package com.demo.payment.domain.channel.spi;

import com.demo.payment.domain.acquiring.model.vo.OutTradeNo;
import com.demo.payment.domain.channel.model.ChannelResultStatus;
import com.demo.payment.shared.money.Money;

/**
 * 查证响应。
 *
 * <p><b>查证是支付系统的定海神针。</b>
 * 所有异步通知都只是"触发器"，真正决定订单终态的是查证结果。
 * 生产环境必须部署查证补偿任务：对超过 N 分钟仍处于"支付中"的订单逐级轮询
 * （10s / 30s / 60s / 5min / 30min / 2h），直到拿到终态或超过通道查询窗口。
 */
public record QueryResponse(
        OutTradeNo outTradeNo,
        ChannelResultStatus status,
        String channelTransactionId,
        String channelRawStatus,
        Money amount,
        String message,
        boolean infrastructureError
) {
    /**
     * 查证时通道明确返回"订单不存在"。
     *
     * <p><b>注意：这不等于支付失败！</b>
     * 下单请求可能根本没到达通道（网络在请求阶段就断了），
     * 此时查单必然返回 NOT_EXIST。正确处理是：
     * 若距下单时间已超过通道的订单创建延迟窗口（通常 30s~5min），
     * 才判定为失败；否则继续等待重试。
     */
    public boolean isOrderNotExist() {
        return "NOT_EXIST".equals(channelRawStatus) || "ORDER_NOT_EXIST".equals(channelRawStatus);
    }

    public static QueryResponse of(OutTradeNo no, ChannelResultStatus status,
                                   String txId, Money amount) {
        return new QueryResponse(no, status, txId, status.name(), amount, null, false);
    }

    public static QueryResponse unknown(OutTradeNo no, String message) {
        return new QueryResponse(no, ChannelResultStatus.UNKNOWN, null, null, null, message, true);
    }
}
'''

F[P + "spi/CloseCommand.java"] = r'''
package com.demo.payment.domain.channel.spi;

import com.demo.payment.domain.acquiring.model.vo.OutTradeNo;

/**
 * 关单命令。
 *
 * <p>关单只应作用于<b>未支付</b>的订单。对已支付订单，通道会拒绝关单
 * （这是通道侧提供的一道保护），但本系统仍在聚合根层面做了前置拦截，
 * 避免无谓的通道调用，也避免"关单成功"的假象误导运营。
 */
public record CloseCommand(OutTradeNo outTradeNo, String reason) {}
'''

F[P + "spi/CloseResponse.java"] = r'''
package com.demo.payment.domain.channel.spi;

import com.demo.payment.domain.acquiring.model.vo.OutTradeNo;

public record CloseResponse(
        OutTradeNo outTradeNo,
        boolean closed,
        String code,
        String message,
        boolean infrastructureError
) {
    public static CloseResponse success(OutTradeNo no) {
        return new CloseResponse(no, true, null, null, false);
    }

    public static CloseResponse fail(OutTradeNo no, String code, String message) {
        return new CloseResponse(no, false, code, message, false);
    }
}
'''

F[P + "spi/RefundCommand.java"] = r'''
package com.demo.payment.domain.channel.spi;

import com.demo.payment.domain.acquiring.model.vo.OutTradeNo;
import com.demo.payment.shared.money.Money;

/**
 * 退款命令。
 *
 * <p><b>为什么需要 outRefundNo？</b>
 * 退款在通道侧是一笔独立的交易，需要独立的幂等标识。
 * 若直接复用 outTradeNo，同一订单多次部分退款就会撞号。
 * outRefundNo 必须在<b>通道维度</b>唯一（不是订单维度）。
 */
public record RefundCommand(
        OutTradeNo outTradeNo,
        String outRefundNo,
        Money amount,
        Money originalAmount,
        String reason,
        String notifyUrl,
        String idempotencyKey
) {}
'''

F[P + "spi/RefundResponse.java"] = r'''
package com.demo.payment.domain.channel.spi;

import com.demo.payment.domain.channel.model.ChannelResultStatus;
import com.demo.payment.shared.money.Money;

/**
 * 退款响应。
 *
 * <p><b>关于退款的同步/异步差异：</b>
 * <ul>
 *   <li>微信/支付宝：退款请求<b>同步返回受理结果</b>，实际到账异步通过 refunds 回调通知。
 *       但注意 —— 同步返回 SUCCESS 只代表"通道受理了"，不代表钱已退到用户账上。</li>
 *   <li>Stripe：同步返回 refund 对象，状态可立即确定。</li>
 *   <li>PayPal：退款同步完成，但资金到账可能有延迟。</li>
 * </ul>
 *
 * <p>因此退款单同样需要"退款中"状态 + 查证补偿，不能同步返回成功就置终态。
 */
public record RefundResponse(
        String outRefundNo,
        ChannelResultStatus status,
        String channelRefundId,
        Money refundedAmount,
        String code,
        String message,
        boolean infrastructureError
) {
    public static RefundResponse succeeded(String outRefundNo, String channelRefundId, Money amount) {
        return new RefundResponse(outRefundNo, ChannelResultStatus.SUCCEEDED,
                channelRefundId, amount, null, null, false);
    }

    public static RefundResponse failed(String outRefundNo, String code, String message) {
        return new RefundResponse(outRefundNo, ChannelResultStatus.FAILED, null,
                null, code, message, false);
    }

    public static RefundResponse unknown(String outRefundNo, String message) {
        return new RefundResponse(outRefundNo, ChannelResultStatus.UNKNOWN, null,
                null, "UNKNOWN", message, true);
    }
}
'''

F[P + "spi/CancelCommand.java"] = r'''
package com.demo.payment.domain.channel.spi;

import com.demo.payment.domain.acquiring.model.vo.OutTradeNo;

/**
 * 撤销（void）命令。
 *
 * <p><b>撤销 ≠ 退款，这个区别在资金上非常关键：</b>
 * <table border="1">
 *   <tr><th></th><th>撤销 void</th><th>退款 refund</th></tr>
 *   <tr><td>时机</td><td>清算前（通常当日）</td><td>清算后</td></tr>
 *   <tr><td>资金流</td><td>冻结额度直接释放，<b>未真正划账</b></td><td>已收款再退回</td></tr>
 *   <tr><td>手续费</td><td><b>通常不收取</b></td><td>通常不退手续费</td></tr>
 *   <tr><td>凭证</td><td>不产生独立退款单</td><td>产生独立退款单</td></tr>
 *   <tr><td>国内通道</td><td><b>基本不支持</b></td><td>支持</td></tr>
 * </table>
 *
 * <p>因此 {@code ChannelCapability.supportsCancel} 是路由与退款策略的重要判断依据：
 * 当日撤销优先走 void（省手续费），隔日只能走 refund。
 */
public record CancelCommand(OutTradeNo outTradeNo, String channelTransactionId, String reason) {}
'''

F[P + "spi/CancelResponse.java"] = r'''
package com.demo.payment.domain.channel.spi;

import com.demo.payment.domain.acquiring.model.vo.OutTradeNo;

public record CancelResponse(
        OutTradeNo outTradeNo,
        boolean cancelled,
        String code,
        String message,
        boolean infrastructureError
) {
    public static CancelResponse success(OutTradeNo no) {
        return new CancelResponse(no, true, null, null, false);
    }

    public static CancelResponse fail(OutTradeNo no, String code, String message) {
        return new CancelResponse(no, false, code, message, false);
    }
}
'''

F[P + "spi/CaptureCommand.java"] = r'''
package com.demo.payment.domain.channel.spi;

import com.demo.payment.domain.acquiring.model.vo.OutTradeNo;
import com.demo.payment.shared.money.Money;

/**
 * 请款命令（两段式通道的第二步）。
 *
 * <p>典型业务：酒店预授权 —— 入住时先授权冻结 1000 元，退房时按实际消费 800 元请款，
 * 剩余 200 元自动解冻。若按一段式实现，就只能"先扣 1000 再退 200"，
 * 多占用户额度、多付手续费、体验也差。
 *
 * <p><b>部分请款</b>：{@code amount} 小于授权金额时，部分通道会自动释放差额，
 * 部分需要显式调用撤销授权。这是适配层必须处理的差异。
 */
public record CaptureCommand(
        OutTradeNo outTradeNo,
        String channelTransactionId,
        Money amount,
        Money authorizedAmount,
        String idempotencyKey
) {}
'''

F[P + "spi/CaptureResponse.java"] = r'''
package com.demo.payment.domain.channel.spi;

import com.demo.payment.domain.channel.model.ChannelResultStatus;
import com.demo.payment.shared.money.Money;

public record CaptureResponse(
        String outTradeNo,
        ChannelResultStatus status,
        String channelTransactionId,
        Money capturedAmount,
        String code,
        String message,
        boolean infrastructureError
) {
    public static CaptureResponse succeeded(String outTradeNo, String txId, Money amount) {
        return new CaptureResponse(outTradeNo, ChannelResultStatus.SUCCEEDED,
                txId, amount, null, null, false);
    }

    public static CaptureResponse failed(String outTradeNo, String code, String message) {
        return new CaptureResponse(outTradeNo, ChannelResultStatus.FAILED, null,
                null, code, message, false);
    }
}
'''

F[P + "spi/RawNotification.java"] = r'''
package com.demo.payment.domain.channel.spi;

import java.util.Map;

/**
 * 通道原始通知报文。
 *
 * <p>这是适配层的输入，保留最原始的信息（body + headers），
 * 因为<b>验签必须基于原始字节流</b> —— 任何先反序列化再验签的做法都是错的：
 * JSON 序列化/反序列化会改变字节序、空格、字段顺序，导致签名校验失败，
 * 更糟的是有人为此"临时"关掉验签，直接把系统敞开给攻击者。
 */
public record RawNotification(
        String body,
        Map<String, String> headers,
        Map<String, String> queryParams,
        String remoteIp
) {
    public static RawNotification of(String body, Map<String, String> headers) {
        return new RawNotification(body, headers, Map.of(), null);
    }

    public String header(String name) {
        return headers == null ? null : headers.get(name);
    }

    /** 大小写不敏感地取 header（HTTP header 名不区分大小写，各通道写法还不同） */
    public String headerIgnoreCase(String name) {
        if (headers == null) { return null; }
        return headers.entrySet().stream()
                .filter(e -> e.getKey() != null && e.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }
}
'''

F[P + "spi/NotificationParseResult.java"] = r'''
package com.demo.payment.domain.channel.spi;

import com.demo.payment.domain.acquiring.model.vo.OutTradeNo;
import com.demo.payment.domain.channel.model.ChannelResultStatus;
import com.demo.payment.shared.money.Money;

import java.time.Instant;

/**
 * 回调解析结果 —— 归一化后的通知。
 *
 * <p>各通道的通知形态差异极大：
 * <pre>
 *   微信 v3   → JSON body + Wechatpay-Signature 头 + 平台证书（需解密 resource 字段）
 *   支付宝    → form-urlencoded + sign 参数（RSA2）
 *   Stripe    → JSON body + Stripe-Signature 头（HMAC-SHA256 + 时间戳防重放）
 *   PayPal    → JSON body + 需二次调用 verify-webhook 验签（PayPal 不提供本地验签）
 *   Worldpay  → <b>XML</b> 通知 + MAC 校验
 *   Antom     → JSON + HMAC-SHA256 签名头
 * </pre>
 *
 * <p>适配层的职责就是把上述所有形态统一成这个结构，上层再也见不到 XML 和 form 编码。
 */
public record NotificationParseResult(
        OutTradeNo outTradeNo,
        String channelTransactionId,
        ChannelResultStatus status,
        String channelRawStatus,
        Money amount,

        /**
         * 通道侧的通知唯一 ID。
         * 用于<b>通知去重</b>：同一笔交易通道可能重投多次（网络重试、补偿推送），
         * 必须按 notifyId 去重，否则会重复触发业务逻辑。
         */
        String notifyId,

        /** 通知类型：payment / refund / dispute（拒付） */
        String notifyType,

        /** 通道侧事件发生时间 */
        Instant occurredAt,

        /** 原始报文，保留以便问题追溯与重放 */
        String rawBody
) {
    public boolean isPaymentNotify() { return "payment".equals(notifyType); }
    public boolean isRefundNotify() { return "refund".equals(notifyType); }
    public boolean isDisputeNotify() { return "dispute".equals(notifyType); }

    /**
     * 是否已有明确的终态结论。
     * 若通道只通知"支付中"，则不更新订单状态，只记日志。
     */
    public boolean hasFinalResult() { return status != null && status.isFinal(); }
}
'''

F[P + "spi/RoutingContext.java"] = r'''
package com.demo.payment.domain.channel.spi;

import com.demo.payment.domain.channel.model.PaymentMethodType;
import com.demo.payment.shared.money.Currency;
import com.demo.payment.shared.money.Money;

/**
 * 路由上下文 —— 智能路由的输入。
 *
 * <p>路由不只看"支付方式"，还要综合金额、币种、地区、商户、终端场景。
 * 例如同样是用 Apple Pay：
 * <ul>
 *   <li>100 元小额 → 走 Stripe（费率低、接入简单）</li>
 *   <li>50000 元大额 → 走 Worldpay（大额成功率高、有 3DS 豁免）</li>
 *   <li>东南亚用户 → 走 Antom（本地收单，成功率高且费率低）</li>
 * </ul>
 */
public record RoutingContext(
        String merchantId,
        PaymentMethodType paymentMethod,
        Money amount,
        Currency currency,
        String countryCode,
        String clientIp,
        /** 终端场景：APP / WEB / H5 / QR / MINI_PROGRAM */
        String scene
) {}
'''

F[P + "route/ChannelRouter.java"] = r'''
package com.demo.payment.domain.channel.route;

import com.demo.payment.domain.channel.model.ChannelCode;
import com.demo.payment.domain.channel.spi.RoutingContext;

import java.util.List;

/**
 * 通道路由 —— 领域服务。
 *
 * <p><b>路由是支付平台的核心竞争力之一。</b>
 * 一个成熟支付平台的路由规则通常包含：
 * <ol>
 *   <li><b>可用性过滤</b>：支付方式支持？币种支持？金额在限额内？商户已开通该通道？</li>
 *   <li><b>成本排序</b>：按费率 + 固定费计算实际成本，取最低</li>
 *   <li><b>成功率排序</b>：基于滑动窗口统计的各通道成功率（近 5 分钟/1 小时/24 小时）</li>
 *   <li><b>熔断降级</b>：连续失败的通道自动降权或摘除，冷却后半开探测</li>
 *   <li><b>灰度分流</b>：新通道按流量比例灰度，观察稳定后全量</li>
 *   <li><b>合规与风控</b>：特定地区/行业强制走特定通道</li>
 * </ol>
 *
 * <p>本接口只定义"选出候选通道列表"的契约，
 * 具体打分逻辑由 {@link RouteStrategy} 实现，便于替换与 A/B 测试。
 */
public interface ChannelRouter {

    /**
     * 按优先级返回可用通道列表。
     *
     * <p><b>为什么返回列表而不是单个通道？</b>
     * 因为要支持<b>失败自动切换</b>：第一个通道失败后，
     * 应用层可以直接取列表中的下一个重试，无需重新走一遍路由计算。
     * 只返回单个通道的设计，会让失败重试变得非常别扭（要重新计算路由，
     * 还可能算出同一个通道）。
     *
     * @return 按优先级降序排列的通道列表，可能为空（表示无可用通道）
     */
    List<ChannelCode> route(RoutingContext context);
}
'''

F[P + "route/CapabilityBasedRouter.java"] = r'''
package com.demo.payment.domain.channel.route;

import com.demo.payment.domain.channel.model.ChannelCapability;
import com.demo.payment.domain.channel.model.ChannelCode;
import com.demo.payment.domain.channel.model.PaymentMethodType;
import com.demo.payment.domain.channel.spi.RoutingContext;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 基于能力矩阵的路由器 —— 默认实现。
 *
 * <p>路由分两阶段：
 * <ol>
 *   <li><b>硬过滤</b>（能力矩阵）：过滤掉不支持该支付方式、币种、金额的通道。
 *       这一步是<b>纯内存判断，零 IO</b>，性能极高。</li>
 *   <li><b>软排序</b>（策略）：按费率、成功率、健康度打分排序。</li>
 * </ol>
 *
 * <p><b>为什么把能力过滤放在领域层而不是配置中心？</b>
 * 因为能力是通道的<b>客观属性</b>（不支持就是不支持），
 * 而费率、权重是<b>运营策略</b>（可以随时调）。
 * 前者写死在代码里由编译期保证，后者放配置中心支持热更新 —— 职责分离。
 */
public class CapabilityBasedRouter implements ChannelRouter {

    private final Map<ChannelCode, ChannelCapability> capabilities = new EnumMap<>(ChannelCode.class);
    private final RouteStrategy strategy;

    public CapabilityBasedRouter(RouteStrategy strategy) {
        this.strategy = Objects.requireNonNull(strategy, "strategy");
    }

    /** 注册通道能力（由 infrastructure 层在启动时装配） */
    public CapabilityBasedRouter register(ChannelCapability capability) {
        capabilities.put(capability.channelCode(), capability);
        return this;
    }

    @Override
    public List<ChannelCode> route(RoutingContext context) {
        Objects.requireNonNull(context, "context");

        // 阶段一：硬过滤
        List<ChannelCode> candidates = capabilities.values().stream()
                .filter(cap -> supports(cap, context))
                .map(ChannelCapability::channelCode)
                .collect(Collectors.toList());

        if (candidates.isEmpty()) {
            return List.of();
        }

        // 阶段二：按策略排序
        return strategy.rank(candidates, context);
    }

    private boolean supports(ChannelCapability cap, RoutingContext ctx) {
        // 规则一：支付方式必须被支持
        if (!cap.supports(ctx.paymentMethod())) {
            return false;
        }
        // 规则二：币种必须被支持
        if (ctx.currency() != null && !cap.supports(ctx.currency())) {
            return false;
        }
        // 规则三：金额必须在通道限额内
        if (ctx.amount() != null && !cap.isAmountInRange(ctx.amount().minorUnits())) {
            return false;
        }
        return true;
    }

    /**
     * 诊断方法：返回每个通道为何被过滤掉。
     *
     * <p><b>这个方法在生产排查中极其有用。</b>
     * "用户说付不了款"时，最需要知道的就是"哪些通道被过滤了、为什么"。
     * 没有它，你只能靠猜。
     */
    public Map<ChannelCode, String> explain(RoutingContext context) {
        Map<ChannelCode, String> result = new LinkedHashMap<>();
        for (ChannelCapability cap : capabilities.values()) {
            if (cap.supports(context.paymentMethod())
                    && (context.currency() == null || cap.supports(context.currency()))
                    && (context.amount() == null || cap.isAmountInRange(context.amount().minorUnits()))) {
                result.put(cap.channelCode(), "AVAILABLE");
            } else if (!cap.supports(context.paymentMethod())) {
                result.put(cap.channelCode(), "不支持支付方式 " + context.paymentMethod());
            } else if (context.currency() != null && !cap.supports(context.currency())) {
                result.put(cap.channelCode(), "不支持币种 " + context.currency().code());
            } else {
                result.put(cap.channelCode(), "金额超出限额");
            }
        }
        return result;
    }

    public Optional<ChannelCapability> capabilityOf(ChannelCode code) {
        return Optional.ofNullable(capabilities.get(code));
    }
}
'''

F[P + "route/RouteStrategy.java"] = r'''
package com.demo.payment.domain.channel.route;

import com.demo.payment.domain.channel.model.ChannelCode;
import com.demo.payment.domain.channel.spi.RoutingContext;

import java.util.List;

/**
 * 路由排序策略。
 *
 * <p>把"排序"从"过滤"中独立出来，是为了支持不同阶段的演进：
 * <ul>
 *   <li>初期：静态权重（配置里的固定优先级）</li>
 *   <li>中期：费率优先 + 成功率加权</li>
 *   <li>成熟：机器学习模型打分 + 多臂老虎机探索</li>
 * </ul>
 *
 * <p>接口不变，替换实现即可，业务代码零改动。
 */
public interface RouteStrategy {

    /** 对候选通道排序，返回按优先级降序的列表 */
    List<ChannelCode> rank(List<ChannelCode> candidates, RoutingContext context);
}
'''

F[P + "route/WeightedRouteStrategy.java"] = r'''
package com.demo.payment.domain.channel.route;

import com.demo.payment.domain.channel.model.ChannelCode;
import com.demo.payment.domain.channel.spi.RoutingContext;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 加权路由策略：费率 + 健康度 + 静态权重 综合打分。
 *
 * <p>打分公式（简化版）：
 * <pre>
 *   score = w1 * (1 - normalizedFeeRate) * 100
 *         + w2 * healthScore
 *         + w3 * staticWeight
 * </pre>
 *
 * <p><b>注意：真实系统的成功率统计必须是滑动窗口的。</b>
 * 用全量历史成功率会导致"强者恒强"——新通道永远拿不到流量，
 * 也就永远无法证明自己。通常做法是保留 5%~10% 的探索流量给新通道。
 */
public class WeightedRouteStrategy implements RouteStrategy {

    /** 费率权重 */
    private final double feeWeight;
    /** 健康度权重 */
    private final double healthWeight;
    /** 静态权重 */
    private final double staticWeight;

    private final Map<ChannelCode, ChannelHealth> health = new EnumMap<>(ChannelCode.class);
    private final Map<ChannelCode, Double> feeRates = new EnumMap<>(ChannelCode.class);
    private final Map<ChannelCode, Integer> staticWeights = new EnumMap<>(ChannelCode.class);

    public WeightedRouteStrategy() {
        this(0.4, 0.4, 0.2);
    }

    public WeightedRouteStrategy(double feeWeight, double healthWeight, double staticWeight) {
        this.feeWeight = feeWeight;
        this.healthWeight = healthWeight;
        this.staticWeight = staticWeight;
        initDefaults();
    }

    private void initDefaults() {
        // 费率：示例值，实际应由运营配置中心下发
        feeRates.put(ChannelCode.WECHAT_PAY, 0.006);
        feeRates.put(ChannelCode.ALIPAY, 0.006);
        feeRates.put(ChannelCode.JD_PAY, 0.007);
        feeRates.put(ChannelCode.UNION_PAY, 0.0055);
        feeRates.put(ChannelCode.PAYPAL, 0.029);
        feeRates.put(ChannelCode.STRIPE, 0.029);
        feeRates.put(ChannelCode.WORLDPAY, 0.0275);
        feeRates.put(ChannelCode.ANTOM, 0.025);

        for (ChannelCode code : ChannelCode.values()) {
            health.put(code, new ChannelHealth());
            staticWeights.put(code, 50);
        }
    }

    @Override
    public List<ChannelCode> rank(List<ChannelCode> candidates, RoutingContext context) {
        return candidates.stream()
                .map(code -> new Scored(code, score(code)))
                .sorted(Comparator.comparingDouble(Scored::score).reversed())
                .map(Scored::code)
                .collect(Collectors.toList());
    }

    private double score(ChannelCode code) {
        double fee = feeRates.getOrDefault(code, 0.03);
        // 费率归一化：假设最高 3%，越低越好
        double feeScore = Math.max(0, (1 - fee / 0.03)) * 100;
        double healthScore = health.getOrDefault(code, new ChannelHealth()).score();
        double staticScore = staticWeights.getOrDefault(code, 50);
        return feeWeight * feeScore + healthWeight * healthScore + staticWeight * staticScore;
    }

    /** 记录通道调用结果，用于健康度统计与熔断 */
    public void record(ChannelCode code, boolean success) {
        health.computeIfAbsent(code, k -> new ChannelHealth()).record(success);
    }

    /** 通道是否已被熔断 */
    public boolean isCircuitOpen(ChannelCode code) {
        ChannelHealth h = health.get(code);
        return h != null && h.isCircuitOpen();
    }

    private record Scored(ChannelCode code, double score) {}

    /**
     * 通道健康度 —— 滑动窗口统计 + 熔断。
     *
     * <p>熔断状态机：CLOSED(正常) → OPEN(熔断，流量全部摘除) → HALF_OPEN(半开，放少量探测流量)
     */
    public static final class ChannelHealth {
        private static final int WINDOW_SIZE = 100;
        private final Deque<Boolean> window = new ArrayDeque<>();
        private int consecutiveFailures = 0;
        private long openedAt = 0L;

        /** 连续失败达到此阈值则熔断 */
        private static final int CIRCUIT_BREAK_THRESHOLD = 10;
        /** 熔断后冷却时间（毫秒） */
        private static final long COOLDOWN_MS = 30_000L;

        public synchronized void record(boolean success) {
            window.addLast(success);
            if (window.size() > WINDOW_SIZE) {
                window.removeFirst();
            }
            if (success) {
                consecutiveFailures = 0;
                openedAt = 0L;
            } else {
                consecutiveFailures++;
                if (consecutiveFailures >= CIRCUIT_BREAK_THRESHOLD && openedAt == 0L) {
                    openedAt = System.currentTimeMillis();
                }
            }
        }

        /** 滑动窗口成功率（0~100） */
        public synchronized double score() {
            if (window.isEmpty()) {
                return 100.0;
            }
            long ok = window.stream().filter(Boolean::booleanValue).count();
            return ok * 100.0 / window.size();
        }

        public synchronized boolean isCircuitOpen() {
            if (openedAt == 0L) {
                return false;
            }
            // 冷却期结束后自动进入半开：先认为未熔断，让少量流量进来探测
            if (System.currentTimeMillis() - openedAt > COOLDOWN_MS) {
                openedAt = 0L;
                consecutiveFailures = 0;
                return false;
            }
            return true;
        }
    }
}
'''

for path, content in F.items():
    full = os.path.join(BASE, path)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    with open(full, "w", encoding="utf-8") as f:
        f.write(content.lstrip("\n"))
    print("WROTE", path)
print("\nTOTAL:", len(F))
