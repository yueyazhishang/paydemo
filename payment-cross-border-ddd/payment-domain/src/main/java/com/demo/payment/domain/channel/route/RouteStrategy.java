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
