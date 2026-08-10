package com.arohtea.business_service.config;

import org.springframework.amqp.core.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
