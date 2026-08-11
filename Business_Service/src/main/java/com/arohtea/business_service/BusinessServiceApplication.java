package com.arohtea.business_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Business Service 启动入口，启用定时清理和事务后异步派发。
 *
 * <p>定时任务负责收口超时分析，异步能力负责 SSE 读取和事务提交后的 RabbitMQ
 * 投递。启动前清理 JVM 级 SOCKS 代理，是为了让本地开发环境直连 PostgreSQL、
 * Redis、RabbitMQ、MinIO 和 AI Service，而不是意外继承桌面代理设置。</p>
 */
@SpringBootApplication
@EnableScheduling
@EnableAsync
public class BusinessServiceApplication {

    /**
     * 清理会影响本地基础设施访问的代理属性，然后启动 Spring 容器。
     *
     * @param args Spring Boot 启动参数
     */
    public static void main(String[] args) {
        // 清除开发机可能继承的 SOCKS 代理，避免本地服务访问内网基础设施时被错误转发。
        System.clearProperty("socksProxyHost");
        System.clearProperty("socksProxyPort");
        System.clearProperty("socksProxyVersion");
        System.clearProperty("java.net.socks.username");
        System.clearProperty("java.net.socks.password");
        SpringApplication.run(BusinessServiceApplication.class, args);
    }

}
