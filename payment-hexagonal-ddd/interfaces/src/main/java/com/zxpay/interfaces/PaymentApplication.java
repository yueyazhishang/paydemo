package com.zxpay.interfaces;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 启动类。
 *
 * <p>{@code scanBasePackages = "com.zxpay"} 是关键：
 * 启动类在 {@code com.zxpay.interfaces}，而适配器与配置在
 * {@code com.zxpay.infrastructure}。只有显式扩大扫描范围，
 * Spring 才能把出站适配器收集进容器。
 *
 * <p>这也体现了装配根的定位：<b>它是唯一知道「全部模块」的地方</b>。
 * 其他任何一层都不知道别层的存在，依赖关系全部在这里汇合。
 */
@SpringBootApplication(scanBasePackages = "com.zxpay")
public class PaymentApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentApplication.class, args);
    }
}
