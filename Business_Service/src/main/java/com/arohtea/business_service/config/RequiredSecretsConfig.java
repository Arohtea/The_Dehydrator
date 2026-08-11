package com.arohtea.business_service.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 在服务启动阶段拒绝关键凭据为空，避免部署后静默回退到弱认证配置。
 */
@Configuration
public class RequiredSecretsConfig {

    private final Map<String, String> secrets;

    /**
     * 收集部署所需凭据，启动后统一执行空值检查。
     *
     * @param postgresPassword PostgreSQL 密码
     * @param redisPassword Redis 密码
     * @param rabbitmqUser RabbitMQ 用户名
     * @param rabbitmqPassword RabbitMQ 密码
     * @param minioAccessKey MinIO Access Key
     * @param minioSecretKey MinIO Secret Key
     * @param internalServiceToken 内部服务令牌
     * @param adminUsername 管理员用户名
     * @param adminPasswordHash 管理员 BCrypt 密码哈希
     */
    public RequiredSecretsConfig(
            @Value("${spring.datasource.password:}") String postgresPassword,
            @Value("${spring.data.redis.password:}") String redisPassword,
            @Value("${spring.rabbitmq.username:}") String rabbitmqUser,
            @Value("${spring.rabbitmq.password:}") String rabbitmqPassword,
            @Value("${minio.access-key:}") String minioAccessKey,
            @Value("${minio.secret-key:}") String minioSecretKey,
            @Value("${ai-service.service-token:}") String internalServiceToken,
            @Value("${security.admin.username:}") String adminUsername,
            @Value("${security.admin.password-hash:}") String adminPasswordHash) {
        this.secrets = new LinkedHashMap<>();
        secrets.put("POSTGRES_PASSWORD", postgresPassword);
        secrets.put("REDIS_PASSWORD", redisPassword);
        secrets.put("RABBITMQ_USER", rabbitmqUser);
        secrets.put("RABBITMQ_PASSWORD", rabbitmqPassword);
        secrets.put("MINIO_ACCESS_KEY", minioAccessKey);
        secrets.put("MINIO_SECRET_KEY", minioSecretKey);
        secrets.put("INTERNAL_SERVICE_TOKEN", internalServiceToken);
        secrets.put("ADMIN_USERNAME", adminUsername);
        secrets.put("ADMIN_PASSWORD_HASH", stripOptionalQuotes(adminPasswordHash));
    }

    /**
     * 在 Spring 初始化后校验必需凭据和管理员 BCrypt 哈希。
     *
     * @throws IllegalStateException 必需值缺失、仍为占位符或哈希格式错误
     */
    @PostConstruct
    void validateSecrets() {
        // 先检查所有必需项，再单独校验 BCrypt 格式，启动失败信息只包含配置名不包含值。
        String missing = secrets.entrySet().stream()
                .filter(entry -> isMissing(entry.getValue()))
                .map(Map.Entry::getKey)
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
        if (!missing.isEmpty()) {
            throw new IllegalStateException("缺少必需 Secret: " + missing);
        }
        String passwordHash = secrets.get("ADMIN_PASSWORD_HASH");
        if (!passwordHash.matches("^\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}$")) {
            throw new IllegalStateException("ADMIN_PASSWORD_HASH 必须是有效的 BCrypt 哈希");
        }
    }

    /**
     * 判断配置是否为空或仍为示例占位符。
     *
     * @param value 配置值
     * @return 需要拒绝启动时返回 true
     */
    private boolean isMissing(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        String normalized = value.trim().toLowerCase();
        return normalized.startsWith("replace-with-") || normalized.startsWith("change-me");
    }

    /**
     * 去掉环境变量值可能携带的一对包裹引号。
     *
     * @param value 原始配置值
     * @return 去掉成对单引号或双引号后的值
     */
    private String stripOptionalQuotes(String value) {
        if (value == null || value.length() < 2) {
            return value;
        }
        char first = value.charAt(0);
        char last = value.charAt(value.length() - 1);
        return (first == last && (first == '\'' || first == '"'))
                ? value.substring(1, value.length() - 1)
                : value;
    }
}
