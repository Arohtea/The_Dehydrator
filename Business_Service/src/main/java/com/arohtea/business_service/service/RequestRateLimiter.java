package com.arohtea.business_service.service;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class RequestRateLimiter {

    private final Bucket uploadBucket = createBucket(10);
    private final Bucket analysisBucket = createBucket(10);

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

    private Bucket createBucket(long capacity) {
        Bandwidth limit = Bandwidth.builder()
                .capacity(capacity)
                .refillGreedy(capacity, Duration.ofMinutes(1))
                .build();
        return Bucket.builder().addLimit(limit).build();
    }
}
