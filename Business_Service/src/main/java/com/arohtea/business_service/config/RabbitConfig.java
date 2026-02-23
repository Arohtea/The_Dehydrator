package com.arohtea.business_service.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    @Bean
    public DirectExchange analysisExchange() {
        return new DirectExchange("analysis.exchange");
    }

    @Bean
    public Queue analysisRequestQueue() {
        return new Queue("analysis.request");
    }

    @Bean
    public Queue analysisResultQueue() {
        return new Queue("analysis.result");
    }

    @Bean
    public Binding requestBinding() {
        return BindingBuilder
                .bind(analysisRequestQueue())
                .to(analysisExchange())
                .with("analysis.request");
    }

    @Bean
    public Binding resultBinding() {
        return BindingBuilder
                .bind(analysisResultQueue())
                .to(analysisExchange())
                .with("analysis.result");
    }

    @Bean
    public Queue analysisProgressQueue() {
        return new Queue("analysis.progress");
    }

    @Bean
    public Binding progressBinding() {
        return BindingBuilder
                .bind(analysisProgressQueue())
                .to(analysisExchange())
                .with("analysis.progress");
    }
}
