package com.demo.payment.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 启动类。
 *
 * <p><b>组件扫描范围</b>：{@code com.demo.payment} 覆盖了所有模块的根包，
 * 因此各模块的 {@code @Component} / {@code @Configuration} 都能被扫描到。
 * 这是多模块 Spring Boot 工程的标准做法 —— 启动器模块依赖所有其他模块，
 * 但其他模块之间不互相依赖启动器。
 */
@SpringBootApplication(scanBasePackages = "com.demo.payment")
public class PaymentApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentApplication.class, args);
    }
}
