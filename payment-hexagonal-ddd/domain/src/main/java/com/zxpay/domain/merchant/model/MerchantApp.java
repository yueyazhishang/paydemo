package com.zxpay.domain.merchant.model;

import com.zxpay.domain.channel.model.ChannelCode;
import com.zxpay.domain.channel.model.PaymentMethod;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 商户应用实体（属于 {@link Merchant} 聚合内部）。
 *
 * <p>持有接入凭证的<b>元数据</b>，但不持有密钥本身——密钥属于基础设施层的
 * 密钥管理服务，领域层只保存「用哪个密钥标识去取」。
 * 让密钥进入领域对象，等于让它有机会被序列化进日志、缓存或事件消息，是典型的安全事故源头。
 */
public final class MerchantApp {

    private final MerchantAppId appId;
    private final String name;

    /** 交易结果回调地址。通道通知经我们转发后最终投递到这里。 */
    private final String notifyUrl;

    /** 商户验签公钥标识。实际公钥由基础设施层按此标识加载。 */
    private final String signKeyId;

    private final List<ChannelContract> contracts;

    public MerchantApp(MerchantAppId appId, String name, String notifyUrl, String signKeyId,
                       List<ChannelContract> contracts) {
        if (appId == null) {
            throw new IllegalArgumentException("appId must not be null");
        }
        this.appId = appId;
        this.name = name;
        this.notifyUrl = notifyUrl;
        this.signKeyId = signKeyId;
        this.contracts = contracts == null ? new ArrayList<>() : new ArrayList<>(contracts);
    }

    public MerchantAppId appId() {
        return appId;
    }

    public String name() {
        return name;
    }

    public String notifyUrl() {
        return notifyUrl;
    }

    public String signKeyId() {
        return signKeyId;
    }

    public List<ChannelContract> contracts() {
        return Collections.unmodifiableList(contracts);
    }

    /**
     * 找出支持指定支付方式的签约通道。
     *
     * <p>路由的第一道过滤：商户没签约的通道，能力再强也用不了。
     * 这一步必须在查能力矩阵<b>之前</b>做——否则会选出一家「支持但没签约」的通道，
     * 下单时才报「商户号不存在」。
     */
    public List<ChannelContract> contractsFor(PaymentMethod method) {
        return contracts.stream()
                .filter(contract -> contract.allows(method))
                .toList();
    }

    public Optional<ChannelContract> contractOf(ChannelCode channel) {
        return contracts.stream()
                .filter(contract -> contract.channel() == channel && contract.enabled())
                .findFirst();
    }

    public boolean supports(PaymentMethod method) {
        return contracts.stream().anyMatch(contract -> contract.allows(method));
    }
}
