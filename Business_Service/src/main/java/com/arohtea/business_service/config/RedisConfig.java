package com.arohtea.business_service.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 配置 SSE 读取和事务后消息派发使用的异步线程池。
 *
 * <p>SSE 会长时间阻塞等待 Redis，消息派发则需要尽快执行；两者分池，避免打开
 * 较多详情页后把任务投递线程全部占满。</p>
 */
@Configuration
public class RedisConfig {

    /**
     * 创建长连接 SSE 读取线程池。
     *
     * @return 专用异步执行器
     */
    @Bean(name = "analysisStreamExecutor")
    public AsyncTaskExecutor analysisStreamExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 长连接数量通常多于任务派发数量，因此给 SSE 更大的基础并发。
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(32);
        // 队列吸收短时连接峰值，但有上限，防止无限排队导致内存增长。
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("analysis-stream-");
        executor.initialize();
        return executor;
    }

    /**
     * 创建分析任务事务提交后派发线程池。
     *
     * @return 专用异步执行器
     */
    @Bean(name = "analysisTaskDispatchExecutor")
    public AsyncTaskExecutor analysisTaskDispatchExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 派发线程只负责很短的 RabbitMQ 调用，少量固定线程即可避免重复投递过快。
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        // 有限队列让基础设施异常时尽快暴露压力，而不是静默堆积任务。
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("analysis-dispatch-");
        executor.initialize();
        return executor;
    }
}
