package com.arohtea.business_service.config;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * 创建 MinIO 对象存储客户端和 Business Service 到 AI Service 的 HTTP 客户端。
 *
 * <p>两个客户端都使用部署配置中的超时和凭据。统一在这里创建，业务服务只依赖
 * Spring 注入的客户端，不会在每次上传或调用 AI Service 时重复组装连接参数。</p>
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
     * <p>连接超时限制建立 TCP 连接的等待时间，读取超时限制对方已经连接后返回响应
     * 的等待时间；两者分开配置，避免外部服务异常时占用业务线程太久。</p>
     *
     * @return 带部署超时配置的 HTTP 客户端
     */
    @Bean
    public RestTemplate restTemplate() {
        // RestTemplate 使用 JDK 请求工厂，所有内部 HTTP 调用共享同一套超时边界。
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        return new RestTemplate(factory);
    }

    /**
     * 创建 MinIO 客户端。
     *
     * <p>MinIO 保存原始文件，业务实体只保存对象路径；后续删除和补偿清理都通过
     * 这个客户端访问同一个 bucket。</p>
     *
     * @return 使用统一部署配置的 MinIO 客户端
     */
    @Bean
    public MinioClient minioClient() {
        // 凭据只在客户端初始化时注入，业务日志不会打印 accessKey 或 secretKey。
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }
}
