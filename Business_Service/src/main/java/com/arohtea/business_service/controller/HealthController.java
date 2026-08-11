package com.arohtea.business_service.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 进程存活检查接口，不主动探测外部基础设施。
 */
@RestController
public class HealthController {

    /**
     * 返回 Business Service 进程存活状态。
     *
     * @return 固定的健康状态
     */
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }
}
