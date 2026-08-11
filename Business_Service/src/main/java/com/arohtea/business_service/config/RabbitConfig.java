package com.arohtea.business_service.config;

import org.springframework.amqp.core.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 声明分析请求、结果和进度共用的 RabbitMQ 交换机、队列和路由绑定。
 *
 * <p>同一个直连交换机承载三条单向消息流：Business Service 发请求，AI Service
 * 回传结果和进度。请求、结果、进度使用独立队列，消费者可以分别处理任务状态和
 * 页面展示，不会因为高频进度消息阻塞最终结果。</p>
 */
@Configuration
public class RabbitConfig {

    private final String exchangeName;
    private final String requestQueueName;
    private final String resultQueueName;
    private final String progressQueueName;

    /**
     * 创建分析消息拓扑配置。
     *
     * @param exchangeName 分析交换机名称
     * @param requestQueueName 请求队列与路由键
     * @param resultQueueName 结果队列与路由键
     * @param progressQueueName 进度队列与路由键
     */
    public RabbitConfig(
            @Value("${messaging.analysis.exchange}") String exchangeName,
            @Value("${messaging.analysis.request-queue}") String requestQueueName,
            @Value("${messaging.analysis.result-queue}") String resultQueueName,
            @Value("${messaging.analysis.progress-queue}") String progressQueueName) {
        this.exchangeName = exchangeName;
        this.requestQueueName = requestQueueName;
        this.resultQueueName = resultQueueName;
        this.progressQueueName = progressQueueName;
    }

    /**
     * 创建分析任务交换机。
     *
     * <p>直连交换机会按照 routing key 精确选择队列，避免一条分析请求被结果消费者
     * 误收到。</p>
     *
     * @return 持久化直连交换机
     */
    @Bean
    public DirectExchange analysisExchange() {
        return new DirectExchange(exchangeName);
    }

    /**
     * 创建分析请求队列。
     *
     * @return 持久化请求队列
     */
    @Bean
    public Queue analysisRequestQueue() {
        return new Queue(requestQueueName);
    }

    /**
     * 创建分析结果队列。
     *
     * @return 持久化结果队列
     */
    @Bean
    public Queue analysisResultQueue() {
        return new Queue(resultQueueName);
    }

    /**
     * 将分析请求队列绑定到统一交换机。
     *
     * <p>请求队列的名称同时作为路由键，AnalysisTaskDispatcher 发送到该键后，AI
     * Service 监听的队列就能收到任务。</p>
     *
     * @return 请求路由绑定
     */
    @Bean
    public Binding requestBinding() {
        return BindingBuilder
                .bind(analysisRequestQueue())
                .to(analysisExchange())
                .with(requestQueueName);
    }

    /**
     * 将分析结果队列绑定到统一交换机。
     *
     * <p>结果消息进入这里后由 AnalysisResultListener 校验并写回任务数据库。</p>
     *
     * @return 结果路由绑定
     */
    @Bean
    public Binding resultBinding() {
        return BindingBuilder
                .bind(analysisResultQueue())
                .to(analysisExchange())
                .with(resultQueueName);
    }

    /**
     * 创建分析进度队列。
     *
     * @return 持久化进度队列
     */
    @Bean
    public Queue analysisProgressQueue() {
        return new Queue(progressQueueName);
    }

    /**
     * 将分析进度队列绑定到统一交换机。
     *
     * <p>进度消息和结果消息分开路由，进度丢失只影响展示，不会改变最终任务语义。</p>
     *
     * @return 进度路由绑定
     */
    @Bean
    public Binding progressBinding() {
        return BindingBuilder
                .bind(analysisProgressQueue())
                .to(analysisExchange())
                .with(progressQueueName);
    }
}
