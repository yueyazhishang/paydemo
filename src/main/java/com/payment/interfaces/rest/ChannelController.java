package com.payment.interfaces.rest;

import com.payment.domain.channel.model.enums.ChannelCode;
import com.payment.infrastructure.channel.ChannelAdapterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 通道管理控制器
 * 
 * 查询支持的支付通道列表
 */
@RestController
@RequestMapping("/api/channels")
@RequiredArgsConstructor
public class ChannelController {
    
    private final ChannelAdapterRegistry channelAdapterRegistry;
    
    /**
     * 获取所有支持的支付通道
     */
    @GetMapping
    public ResponseEntity<List<ChannelInfo>> getAllChannels() {
        List<ChannelInfo> channels = channelAdapterRegistry.getRegisteredChannels().stream()
                .map(code -> ChannelInfo.builder()
                        .code(code.getCode())
                        .name(code.getDisplayName())
                        .region(code.getRegion().name())
                        .enabled(code.isEnabled())
                        .build())
                .toList();
        
        return ResponseEntity.ok(channels);
    }
    
    /**
     * 获取国内通道
     */
    @GetMapping("/domestic")
    public ResponseEntity<List<ChannelInfo>> getDomesticChannels() {
        List<ChannelInfo> channels = channelAdapterRegistry.getDomesticAdapters().stream()
                .map(adapter -> {
                    ChannelCode code = adapter.getChannelCode();
                    return ChannelInfo.builder()
                            .code(code.getCode())
                            .name(code.getDisplayName())
                            .region(code.getRegion().name())
                            .enabled(code.isEnabled())
                            .build();
                })
                .toList();
        
        return ResponseEntity.ok(channels);
    }
    
    /**
     * 获取国际通道
     */
    @GetMapping("/international")
    public ResponseEntity<List<ChannelInfo>> getInternationalChannels() {
        List<ChannelInfo> channels = channelAdapterRegistry.getInternationalAdapters().stream()
                .map(adapter -> {
                    ChannelCode code = adapter.getChannelCode();
                    return ChannelInfo.builder()
                            .code(code.getCode())
                            .name(code.getDisplayName())
                            .region(code.getRegion().name())
                            .enabled(code.isEnabled())
                            .build();
                })
                .toList();
        
        return ResponseEntity.ok(channels);
    }
    
    @lombok.Data
    @lombok.Builder
    public static class ChannelInfo {
        private String code;
        private String name;
        private String region;
        private boolean enabled;
    }
}
