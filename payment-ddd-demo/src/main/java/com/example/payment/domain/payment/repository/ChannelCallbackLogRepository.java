package com.example.payment.domain.payment.repository;

import com.example.payment.domain.payment.model.ChannelCallbackLog;

/**
 * 回调留痕仓储接口。
 */
public interface ChannelCallbackLogRepository {

    ChannelCallbackLog save(ChannelCallbackLog log);
}
