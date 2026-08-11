package com.arohtea.business_service.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 配置 SSE 读取和事务后消息派发使用的异步线程池。
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
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(32);
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
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("analysis-dispatch-");
        executor.initialize();
        return executor;
    }
}
