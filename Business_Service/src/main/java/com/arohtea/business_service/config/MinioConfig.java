package com.arohtea.business_service.config;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * 创建 MinIO 对象存储客户端和 Business Service 到 AI Service 的 HTTP 客户端。
 */
@Configuration
public class MinioConfig {

    private final String endpoint;
    private final String accessKey;
    private final String secretKey;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    /**
     * 创建对象存储与内部 HTTP 客户端配置。
     *
     * @param endpoint MinIO API 地址
     * @param accessKey MinIO 账户
     * @param secretKey MinIO 密码
     * @param connectTimeoutMs 连接超时毫秒数
     * @param readTimeoutMs 读取超时毫秒数
     */
    public MinioConfig(
            @Value("${minio.endpoint}") String endpoint,
            @Value("${minio.access-key}") String accessKey,
            @Value("${minio.secret-key}") String secretKey,
            @Value("${http-client.connect-timeout-ms}") int connectTimeoutMs,
            @Value("${http-client.read-timeout-ms}") int readTimeoutMs) {
        this.endpoint = endpoint;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    /**
     * 创建调用 AI Service 使用的 HTTP 客户端。
     *
     * @return 带部署超时配置的 HTTP 客户端
     */
    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        return new RestTemplate(factory);
    }

    /**
     * 创建 MinIO 客户端。
     *
     * @return 使用统一部署配置的 MinIO 客户端
     */
    @Bean
    public MinioClient minioClient() {
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }
}
