package com.example.payment.infrastructure.persistence;

import com.example.payment.domain.payment.model.ChannelCallbackLog;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 渠道回调留痕 PO。
 */
@Getter
@Setter
@Entity
@Table(name = "channel_callback_log")
public class ChannelCallbackLogPO {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "log_id", nullable = false, unique = true, length = 64)
    private String logId;

    @Column(name = "channel", nullable = false, length = 32)
    private String channel;

    @Column(name = "our_trade_no", length = 64)
    private String ourTradeNo;

    @Column(name = "callback_type", nullable = false, length = 32)
    private String callbackType;

    @Column(name = "sign_verified", nullable = false)
    private boolean signVerified;

    @Column(name = "trade_success", nullable = false)
    private boolean tradeSuccess;

    @Enumerated(EnumType.STRING)
    @Column(name = "result", nullable = false, length = 32)
    private ChannelCallbackLog.ProcessResult result;

    @Column(name = "error_message", length = 512)
    private String errorMessage;

    @Column(name = "raw_body", columnDefinition = "TEXT")
    private String rawBody;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;
}
