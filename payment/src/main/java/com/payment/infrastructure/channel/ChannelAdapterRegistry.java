package com.payment.infrastructure.channel;

import com.payment.domain.channel.model.enums.ChannelCode;
import com.payment.infrastructure.channel.adapter.PaymentChannelAdapter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.*;

/**
 * 通道适配器注册表/路由器
 * 
 * 使用策略模式，根据通道编码路由到对应的适配器实现
 * 
 * 设计思路:
 * 1. 所有适配器在启动时注册
 * 2. 根据ChannelCode快速查找对应适配器
 * 3. 新增适配器只需实现接口并注册，符合开闭原则
 */
@Slf4j
@Component
public class ChannelAdapterRegistry {
    
    private final Map<ChannelCode, PaymentChannelAdapter> adapterMap = new HashMap<>();
    
    private final List<PaymentChannelAdapter> adapters;
    
    public ChannelAdapterRegistry(List<PaymentChannelAdapter> adapters) {
        this.adapters = adapters;
    }
    
    /**
     * 初始化注册表
     */
    @PostConstruct
    public void init() {
        log.info("初始化支付通道适配器注册表");
        
        for (PaymentChannelAdapter adapter : adapters) {
            ChannelCode channelCode = adapter.getChannelCode();
            adapterMap.put(channelCode, adapter);
            log.info("注册支付通道适配器: {} -> {}", channelCode.getCode(), adapter.getClass().getSimpleName());
        }
        
        log.info("支付通道适配器注册完成，共 {} 个适配器", adapterMap.size());
    }
    
    /**
     * 根据通道编码获取适配器
     */
    public PaymentChannelAdapter getAdapter(ChannelCode channelCode) {
        PaymentChannelAdapter adapter = adapterMap.get(channelCode);
        if (adapter == null) {
            throw new UnsupportedOperationException(
                "不支持的支付通道: " + channelCode.getCode() + 
                "，可用通道: " + adapterMap.keySet());
        }
        return adapter;
    }
    
    /**
     * 根据通道编码字符串获取适配器
     */
    public PaymentChannelAdapter getAdapter(String channelCode) {
        return getAdapter(ChannelCode.fromCode(channelCode));
    }
    
    /**
     * 获取所有已注册的通道编码
     */
    public Set<ChannelCode> getRegisteredChannels() {
        return Collections.unmodifiableSet(adapterMap.keySet());
    }
    
    /**
     * 检查通道是否已注册
     */
    public boolean isChannelSupported(ChannelCode channelCode) {
        return adapterMap.containsKey(channelCode);
    }
    
    /**
     * 获取所有国内通道
     */
    public List<PaymentChannelAdapter> getDomesticAdapters() {
        return adapterMap.entrySet().stream()
                .filter(e -> e.getKey().getRegion() == ChannelCode.Region.DOMESTIC)
                .map(Map.Entry::getValue)
                .toList();
    }
    
    /**
     * 获取所有国际通道
     */
    public List<PaymentChannelAdapter> getInternationalAdapters() {
        return adapterMap.entrySet().stream()
                .filter(e -> e.getKey().getRegion() == ChannelCode.Region.INTERNATIONAL)
                .map(Map.Entry::getValue)
                .toList();
    }
}
