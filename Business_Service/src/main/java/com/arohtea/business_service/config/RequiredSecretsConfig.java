package com.arohtea.business_service.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 在服务启动阶段拒绝空凭据，避免部署后静默回退到弱认证配置。
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
     * @param adminPasswordHash 管理员 BCrypt 密码哈希
     */
    public RequiredSecretsConfig(
            @Value("${POSTGRES_PASSWORD:}") String postgresPassword,
            @Value("${REDIS_PASSWORD:}") String redisPassword,
            @Value("${RABBITMQ_USER:}") String rabbitmqUser,
            @Value("${RABBITMQ_PASSWORD:}") String rabbitmqPassword,
            @Value("${MINIO_ACCESS_KEY:}") String minioAccessKey,
            @Value("${MINIO_SECRET_KEY:}") String minioSecretKey,
            @Value("${INTERNAL_SERVICE_TOKEN:}") String internalServiceToken,
            @Value("${ADMIN_PASSWORD_HASH:}") String adminPasswordHash) {
        this.secrets = new LinkedHashMap<>();
        secrets.put("POSTGRES_PASSWORD", postgresPassword);
        secrets.put("REDIS_PASSWORD", redisPassword);
        secrets.put("RABBITMQ_USER", rabbitmqUser);
        secrets.put("RABBITMQ_PASSWORD", rabbitmqPassword);
        secrets.put("MINIO_ACCESS_KEY", minioAccessKey);
        secrets.put("MINIO_SECRET_KEY", minioSecretKey);
        secrets.put("INTERNAL_SERVICE_TOKEN", internalServiceToken);
        secrets.put("ADMIN_PASSWORD_HASH", adminPasswordHash);
    }

    @PostConstruct
    void validateSecrets() {
        String missing = secrets.entrySet().stream()
                .filter(entry -> entry.getValue() == null || entry.getValue().isBlank())
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
}
