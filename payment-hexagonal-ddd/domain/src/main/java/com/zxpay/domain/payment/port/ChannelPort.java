package com.zxpay.domain.payment.port;

import com.zxpay.domain.channel.model.ChannelCode;

/**
 * 通道端口基类。
 *
 * <p><b>端口隔离原则（Interface Segregation）在支付领域的具体应用。</b>
 *
 * <p>常见错误是定义一个「万能网关接口」：
 * <pre>{@code
 * interface ChannelGateway {
 *     Result pay(req); Result query(req); Result refund(req);
 *     Result capture(req); Result voidAuth(req); Result close(req); ...
 * }
 * }</pre>
 * 结果是每接一家新通道，都要把不支持的方法实现成
 * {@code throw new UnsupportedOperationException()}。这种设计有两个恶果：
 * <ol>
 *   <li>调用方无法在<b>编译期</b>知道某通道是否支持某能力，只能运行时炸。</li>
 *   <li>{@code UnsupportedOperationException} 满天飞后，
 *       就再也没人分得清「这个能力真的不支持」和「这里还没实现」。</li>
 * </ol>
 *
 * <p>正确做法：一个能力一个端口。适配器只实现自己真正具备的能力，
 * 由 {@link ChannelGatewayRegistry} 按 {@code ChannelCapability} 声明分派。
 * 「这家通道支不支持请款」这个问题，答案在<b>能力矩阵</b>里，不在代码分支里。
 */
public interface ChannelPort {

    /** 该实现所服务的通道。注册表以此建立索引。 */
    ChannelCode channel();
}
