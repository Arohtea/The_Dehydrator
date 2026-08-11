package com.arohtea.business_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Business Service 启动入口，启用定时清理和事务后异步派发。
 */
@SpringBootApplication
@EnableScheduling
@EnableAsync
public class BusinessServiceApplication {

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
