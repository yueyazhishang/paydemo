package com.example.payment.infrastructure.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * 基础设施能力开关：定时调度（掉单补偿/通知重试）与事务管理。
 */
@Configuration
@EnableScheduling
@EnableTransactionManagement
public class SchedulingConfig {
}
