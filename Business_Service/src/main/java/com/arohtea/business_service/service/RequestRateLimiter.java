package com.arohtea.business_service.service;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class RequestRateLimiter {

    private final Bucket uploadBucket;
    private final Bucket analysisBucket;

    /**
     * 创建上传和分析启动限流器。
     *
     * @param uploadCapacity 每个时间窗口允许的上传请求数
     * @param analysisCapacity 每个时间窗口允许的分析启动请求数
     * @param windowSeconds 限流窗口秒数
     */
    public RequestRateLimiter(
            @Value("${analysis.upload-rate-capacity}") long uploadCapacity,
            @Value("${analysis.start-rate-capacity}") long analysisCapacity,
            @Value("${analysis.rate-window-seconds}") long windowSeconds) {
        this.uploadBucket = createBucket(uploadCapacity, windowSeconds);
        this.analysisBucket = createBucket(analysisCapacity, windowSeconds);
    }

    /**
     * 限制文档上传请求，避免单个管理员在短时间内占满解析和向量化资源。
     *
     * @return 当前请求是否允许继续
     */
    public boolean allowUpload() {
        return uploadBucket.tryConsume(1);
    }

    /**
     * 限制分析启动请求，避免重复触发高成本 LLM 调用。
     *
     * @return 当前请求是否允许继续
     */
    public boolean allowAnalysisStart() {
        return analysisBucket.tryConsume(1);
    }

    private Bucket createBucket(long capacity, long windowSeconds) {
        Bandwidth limit = Bandwidth.builder()
                .capacity(capacity)
                .refillGreedy(capacity, Duration.ofSeconds(windowSeconds))
                .build();
        return Bucket.builder().addLimit(limit).build();
    }
}
