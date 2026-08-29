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
