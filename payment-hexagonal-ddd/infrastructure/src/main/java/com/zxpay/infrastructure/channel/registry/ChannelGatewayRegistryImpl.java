package com.zxpay.infrastructure.channel.registry;

import com.zxpay.domain.channel.model.ChannelCode;
import com.zxpay.domain.notify.port.ChannelNotifyParser;
import com.zxpay.domain.notify.port.ChannelNotifyVerifier;
import com.zxpay.domain.payment.port.ChannelCapturePort;
import com.zxpay.domain.payment.port.ChannelClosePort;
import com.zxpay.domain.payment.port.ChannelGatewayRegistry;
import com.zxpay.domain.payment.port.ChannelPaymentPort;
import com.zxpay.domain.payment.port.ChannelQueryPort;
import com.zxpay.domain.payment.port.ChannelReversePort;
import com.zxpay.domain.payment.port.ChannelVoidPort;
import com.zxpay.domain.refund.port.ChannelRefundPort;
import com.zxpay.domain.refund.port.ChannelRefundQueryPort;
import com.zxpay.infrastructure.channel.notify.DefaultChannelNotifyHandler;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 通道端口注册表实现。
 *
 * <p>把「通道 → 它实现了哪些能力端口」的映射集中管理。
 * 领域层只问 {@code capturePortOf(STRIPE)}，得到 {@code Optional}——
 * 为空就说明这家通道不支持请款，上层据此返回明确错误，而不是抛异常。
 *
 * <p><b>这就是「能力驱动分派」的落地点：</b>
 * 适配器只实现自己真正具备的能力，注册表按端口类型建索引，
 * 业务代码从不写 {@code if (channel == STRIPE)}。
 *
 * <p>新增一家通道时，这里<b>一行都不用改</b>——
 * Spring 会自动把所有 {@code ChannelPort} 实现类收集进 List。
 * 这正是依赖注入与端口隔离结合带来的收益。
 */
@Component
public class ChannelGatewayRegistryImpl implements ChannelGatewayRegistry {

    private final Map<ChannelCode, ChannelPaymentPort> paymentPorts = new EnumMap<>(ChannelCode.class);
    private final Map<ChannelCode, ChannelQueryPort> queryPorts = new EnumMap<>(ChannelCode.class);
    private final Map<ChannelCode, ChannelCapturePort> capturePorts = new EnumMap<>(ChannelCode.class);
    private final Map<ChannelCode, ChannelVoidPort> voidPorts = new EnumMap<>(ChannelCode.class);
    private final Map<ChannelCode, ChannelReversePort> reversePorts = new EnumMap<>(ChannelCode.class);
    private final Map<ChannelCode, ChannelClosePort> closePorts = new EnumMap<>(ChannelCode.class);

    private final Map<ChannelCode, ChannelRefundPort> refundPorts = new EnumMap<>(ChannelCode.class);
    private final Map<ChannelCode, ChannelRefundQueryPort> refundQueryPorts = new EnumMap<>(ChannelCode.class);

    /** 回调处理器：由通用处理器按通道生成。 */
    private final Map<ChannelCode, DefaultChannelNotifyHandler> notifyHandlers = new EnumMap<>(ChannelCode.class);

    public ChannelGatewayRegistryImpl(List<ChannelPaymentPort> paymentPortList,
                                      List<ChannelQueryPort> queryPortList,
                                      List<ChannelCapturePort> capturePortList,
                                      List<ChannelVoidPort> voidPortList,
                                      List<ChannelReversePort> reversePortList,
                                      List<ChannelClosePort> closePortList,
                                      List<ChannelRefundPort> refundPortList,
                                      List<ChannelRefundQueryPort> refundQueryPortList) {
        paymentPortList.forEach(p -> paymentPorts.put(p.channel(), p));
        queryPortList.forEach(p -> queryPorts.put(p.channel(), p));
        capturePortList.forEach(p -> capturePorts.put(p.channel(), p));
        voidPortList.forEach(p -> voidPorts.put(p.channel(), p));
        reversePortList.forEach(p -> reversePorts.put(p.channel(), p));
        closePortList.forEach(p -> closePorts.put(p.channel(), p));
        refundPortList.forEach(p -> refundPorts.put(p.channel(), p));
        refundQueryPortList.forEach(p -> refundQueryPorts.put(p.channel(), p));

        for (ChannelCode channel : ChannelCode.values()) {
            notifyHandlers.put(channel, DefaultChannelNotifyHandler.of(channel));
        }
    }

    @Override public Optional<ChannelPaymentPort> paymentPortOf(ChannelCode channel) {
        return Optional.ofNullable(paymentPorts.get(channel));
    }

    @Override public Optional<ChannelQueryPort> queryPortOf(ChannelCode channel) {
        return Optional.ofNullable(queryPorts.get(channel));
    }

    @Override public Optional<ChannelCapturePort> capturePortOf(ChannelCode channel) {
        return Optional.ofNullable(capturePorts.get(channel));
    }

    @Override public Optional<ChannelVoidPort> voidPortOf(ChannelCode channel) {
        return Optional.ofNullable(voidPorts.get(channel));
    }

    @Override public Optional<ChannelReversePort> reversePortOf(ChannelCode channel) {
        return Optional.ofNullable(reversePorts.get(channel));
    }

    @Override public Optional<ChannelClosePort> closePortOf(ChannelCode channel) {
        return Optional.ofNullable(closePorts.get(channel));
    }

    @Override public Optional<ChannelRefundPort> refundPortOf(ChannelCode channel) {
        return Optional.ofNullable(refundPorts.get(channel));
    }

    @Override public Optional<ChannelRefundQueryPort> refundQueryPortOf(ChannelCode channel) {
        return Optional.ofNullable(refundQueryPorts.get(channel));
    }

    @Override public Optional<ChannelNotifyVerifier> verifierOf(ChannelCode channel) {
        return Optional.ofNullable(notifyHandlers.get(channel));
    }

    @Override public Optional<ChannelNotifyParser> parserOf(ChannelCode channel) {
        return Optional.ofNullable(notifyHandlers.get(channel));
    }

    /** 启动时自检：打印每家通道注册了哪些能力端口，配置缺失一眼可见。 */
    public String describeRegistry() {
        StringBuilder sb = new StringBuilder();
        for (ChannelCode channel : ChannelCode.values()) {
            sb.append(channel.name()).append(" => [");
            if (paymentPorts.containsKey(channel)) { sb.append("PAY "); }
            if (queryPorts.containsKey(channel)) { sb.append("QUERY "); }
            if (capturePorts.containsKey(channel)) { sb.append("CAPTURE "); }
            if (voidPorts.containsKey(channel)) { sb.append("VOID "); }
            if (reversePorts.containsKey(channel)) { sb.append("REVERSE "); }
            if (closePorts.containsKey(channel)) { sb.append("CLOSE "); }
            sb.append("]\n");
        }
        return sb.toString();
    }
}
