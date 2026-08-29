-- 支付单表
CREATE TABLE IF NOT EXISTS payment_order (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    payment_id      VARCHAR(64)  NOT NULL COMMENT '支付单号(全局唯一)',
    biz_order_no    VARCHAR(64)  NOT NULL COMMENT '业务订单号(幂等键)',
    merchant_id     VARCHAR(64)  NOT NULL COMMENT '商户号',
    amount          BIGINT       NOT NULL COMMENT '支付金额(最小货币单位)',
    currency        VARCHAR(8)   NOT NULL COMMENT '币种 ISO4217',
    channel         VARCHAR(32)  NOT NULL COMMENT '支付渠道',
    status          VARCHAR(32)  NOT NULL COMMENT '状态: INIT/PAYING/SUCCESS/FAILED/CLOSED',
    channel_trade_no VARCHAR(64) NULL COMMENT '渠道流水号',
    pay_type        VARCHAR(32)  NULL COMMENT '收银台类型: QR_CODE/CASHIER/JSAPI/REDIRECT',
    pay_params      TEXT         NULL COMMENT '渠道收银台参数(JSON)',
    fail_reason     VARCHAR(255) NULL,
    merchant_notify_url VARCHAR(512) NULL COMMENT '上游业务方通知地址',
    version         BIGINT       NOT NULL DEFAULT 0 COMMENT '乐观锁',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_payment_id (payment_id),
    UNIQUE KEY uk_biz_order_no (biz_order_no, channel),
    KEY idx_channel_status (channel, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='支付单聚合';

-- 退款单表
CREATE TABLE IF NOT EXISTS refund_order (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    refund_id       VARCHAR(64)  NOT NULL COMMENT '退款单号',
    payment_id      VARCHAR(64)  NOT NULL COMMENT '原支付单号',
    refund_amount   BIGINT       NOT NULL COMMENT '退款金额(最小货币单位)',
    currency        VARCHAR(8)   NOT NULL,
    status          VARCHAR(32)  NOT NULL COMMENT 'INIT/SUBMITTED/SUCCESS/FAILED',
    channel_refund_no VARCHAR(64) NULL COMMENT '渠道退款流水号',
    reason          VARCHAR(255) NULL,
    version         BIGINT       NOT NULL DEFAULT 0,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_refund_id (refund_id),
    KEY idx_payment_id (payment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='退款单聚合';

-- 渠道回调留痕表（回调链路审计基准）
CREATE TABLE IF NOT EXISTS channel_callback_log (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    log_id          VARCHAR(64)  NOT NULL COMMENT '留痕日志号',
    channel         VARCHAR(32)  NOT NULL,
    our_trade_no    VARCHAR(64)  NULL COMMENT '我方单号(解析失败时为空)',
    callback_type   VARCHAR(32)  NOT NULL COMMENT 'PAYMENT/REFUND/UNKNOWN',
    sign_verified   TINYINT(1)   NOT NULL DEFAULT 0,
    trade_success   TINYINT(1)   NOT NULL DEFAULT 0,
    result          VARCHAR(32)  NOT NULL COMMENT 'SUCCESS/IGNORED_DUPLICATE/SIGN_FAILED/...',
    error_message   VARCHAR(512) NULL,
    raw_body        TEXT         NULL COMMENT '渠道原始报文',
    received_at     DATETIME(6)  NOT NULL,
    KEY idx_log_channel_date (channel, received_at),
    KEY idx_log_trade_no (our_trade_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='渠道回调留痕';

-- 上游通知任务表（支付/退款终态异步通知业务方）
CREATE TABLE IF NOT EXISTS merchant_notify_task (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id         VARCHAR(64)  NOT NULL COMMENT '任务号',
    related_trade_no VARCHAR(64) NOT NULL COMMENT '关联支付单号/退款单号',
    event_type      VARCHAR(64)  NOT NULL COMMENT 'PAYMENT_SUCCEEDED/REFUND_SUCCEEDED/PAYMENT_CLOSED',
    notify_url      VARCHAR(512) NOT NULL,
    payload         TEXT         NULL COMMENT '通知报文JSON',
    status          VARCHAR(32)  NOT NULL COMMENT 'WAITING/SUCCESS/EXHAUSTED',
    retry_count     INT          NOT NULL DEFAULT 0,
    next_retry_time DATETIME(6)  NOT NULL,
    last_error_message VARCHAR(512) NULL,
    created_at      DATETIME(6)  NOT NULL,
    UNIQUE KEY uk_task_id (task_id),
    KEY idx_notify_due (status, next_retry_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='上游通知任务';

-- 对账批次表
CREATE TABLE IF NOT EXISTS reconciliation_batch (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_no      VARCHAR(64) NOT NULL COMMENT '批次号: {channel}_{date}',
    channel       VARCHAR(32) NOT NULL,
    bill_date     DATE        NOT NULL,
    status        VARCHAR(32) NOT NULL COMMENT 'INIT/DOWNLOADING/CHECKING/DONE',
    local_count   INT         NOT NULL DEFAULT 0,
    channel_count INT         NOT NULL DEFAULT 0,
    diff_count    INT         NOT NULL DEFAULT 0,
    created_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_batch (batch_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对账批次';

-- 对账差异明细表
CREATE TABLE IF NOT EXISTS reconciliation_item (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_no      VARCHAR(64)  NOT NULL,
    diff_type     VARCHAR(32)  NOT NULL COMMENT 'LOCAL_MORE/CHANNEL_MORE/AMOUNT_MISMATCH',
    biz_order_no  VARCHAR(64)  NULL,
    local_amount  BIGINT       NULL,
    channel_amount BIGINT      NULL,
    remark        VARCHAR(255) NULL,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_batch (batch_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对账差异明细';
