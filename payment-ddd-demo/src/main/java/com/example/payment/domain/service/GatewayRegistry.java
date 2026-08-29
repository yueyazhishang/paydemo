package com.example.payment.domain.service;

import com.example.payment.domain.gateway.BillDownloader;
import com.example.payment.domain.gateway.PaymentGateway;
import com.example.payment.domain.shared.Channel;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 渠道网关注册表（领域服务，策略模式）。
 * Spring 自动注入所有 PaymentGateway / BillDownloader 实现（即各渠道适配器），
 * 应用层/领域层通过 Channel 枚举取用，不感知具体适配器类型。
 */
@Component
public class GatewayRegistry {

    private final Map<Channel, PaymentGateway> gatewayMap = new EnumMap<>(Channel.class);
    private final Map<Channel, BillDownloader> billDownloaderMap = new EnumMap<>(Channel.class);

    public GatewayRegistry(List<PaymentGateway> gateways, List<BillDownloader> billDownloaders) {
        for (PaymentGateway gateway : gateways) {
            gatewayMap.put(gateway.channel(), gateway);
        }
        for (BillDownloader downloader : billDownloaders) {
            billDownloaderMap.put(downloader.channel(), downloader);
        }
    }

    public PaymentGateway getGateway(Channel channel) {
        PaymentGateway gateway = gatewayMap.get(channel);
        if (gateway == null) {
            throw new IllegalArgumentException("渠道未接入或未注册: " + channel);
        }
        return gateway;
    }

    public BillDownloader getBillDownloader(Channel channel) {
        BillDownloader downloader = billDownloaderMap.get(channel);
        if (downloader == null) {
            throw new IllegalArgumentException("渠道账单下载未接入: " + channel);
        }
        return downloader;
    }
}
