package com.example.payment.domain.payment.model;

import com.example.payment.domain.gateway.CallbackType;
import lombok.Getter;

import java.time.Instant;

/**
 * 渠道回调留痕实体（不属于聚合，独立持久化）。
 * 回调链路的审计基准：无论验签成败、订单是否存在，回调原始报文必须全量落库，
 * 支撑渠道争议仲裁、问题回放与对账排查。
 */
@Getter
public class ChannelCallbackLog {

    public enum ProcessResult { SUCCESS, IGNORED_DUPLICATE, SIGN_FAILED, PARSE_FAILED, ORDER_NOT_FOUND, AMOUNT_MISMATCH, ERROR }

    private String logId;

    private String channel;

    /** 我方单号（解析失败时可能为空） */
    private String ourTradeNo;

    private String callbackType;

    private boolean signVerified;

    /** 是否成功（渠道语义） */
    private boolean tradeSuccess;

    private ProcessResult result;

    private String errorMessage;

    private String rawBody;

    private Instant receivedAt;

    public static ChannelCallbackLog record(String channel, CallbackType callbackType,
                                            String ourTradeNo, boolean signVerified,
                                            boolean tradeSuccess, ProcessResult result,
                                            String errorMessage, String rawBody) {
        ChannelCallbackLog log = new ChannelCallbackLog();
        log.logId = "CB" + java.util.UUID.randomUUID().toString().replace("-", "").toUpperCase();
        log.channel = channel;
        log.callbackType = callbackType != null ? callbackType.name() : "UNKNOWN";
        log.ourTradeNo = ourTradeNo;
        log.signVerified = signVerified;
        log.tradeSuccess = tradeSuccess;
        log.result = result;
        log.errorMessage = errorMessage;
        log.rawBody = rawBody;
        log.receivedAt = Instant.now();
        return log;
    }
}
