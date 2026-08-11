package com.arohtea.business_service.service;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 为上传和分析启动分别维护进程内令牌桶限流器。
 *
 * <p>上传会占用对象存储和向量化资源，分析启动会触发高成本模型调用，因此两类
 * 请求使用不同的桶，互相不会因为一类流量过大而完全阻塞。令牌桶只在当前进程内
 * 生效，适合本项目单实例管理员场景，不等同于跨实例限流。</p>
 */
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
        // 两个桶独立计数：连续上传不会消耗分析启动的额度。
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

    /**
     * 按固定容量和时间窗口创建贪心补充令牌桶。
     *
     * <p>桶满时代表窗口内还有足够额度；每次请求消耗一个令牌，令牌按时间逐步
     * 补充，而不是等整个窗口结束后一次性恢复，避免窗口边界出现突发流量。</p>
     *
     * @param capacity 窗口容量
     * @param windowSeconds 令牌补充窗口秒数
     * @return 配置完成的令牌桶
     */
    private Bucket createBucket(long capacity, long windowSeconds) {
        // capacity 控制短时间最多允许多少次请求，refillGreedy 控制令牌恢复速度。
        Bandwidth limit = Bandwidth.builder()
                .capacity(capacity)
                .refillGreedy(capacity, Duration.ofSeconds(windowSeconds))
                .build();
        return Bucket.builder().addLimit(limit).build();
    }
}
