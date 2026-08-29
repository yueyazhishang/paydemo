package com.zxpay.domain.channel.port;

import com.zxpay.domain.channel.model.ChannelCode;
import com.zxpay.domain.channel.model.ChannelHealth;

import java.util.Collection;

/**
 * 出站端口：通道运行期健康度查询。
 *
 * <p>数据来源通常是监控指标（Prometheus）、滑动窗口计数器或 Redis 中的实时统计。
 * 领域层不关心这些，只关心「这家通道现在健康吗」。
 */
public interface ChannelHealthQuery {

    ChannelHealth healthOf(ChannelCode channel);

    Collection<ChannelHealth> healthOfAll();
}
