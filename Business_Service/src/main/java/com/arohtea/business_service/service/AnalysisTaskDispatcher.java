package com.arohtea.business_service.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** 在分析任务记录提交后投递 RabbitMQ，避免 AI 结果先于任务记录落库。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisTaskDispatcher {

    private final RabbitTemplate rabbitTemplate;
    private final AnalysisTaskStateService stateService;

    @Value("${messaging.analysis.exchange}")
    private String analysisExchange;
    @Value("${messaging.analysis.request-queue}")
    private String analysisRequestQueue;

    /**
     * 事务提交后异步投递分析请求。
     *
     * @param event 已提交的任务消息
     */
    @Async("analysisTaskDispatchExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void dispatch(AnalysisTaskCreatedEvent event) {
        stateService.markProcessing(event.taskId());
        try {
            rabbitTemplate.convertAndSend(analysisExchange, analysisRequestQueue, event.message());
            log.info("分析任务已投递: {}", event.taskId());
        } catch (Exception exception) {
            log.error("RabbitMQ 发送分析任务失败: {}", event.taskId(), exception);
            stateService.markDispatchFailed(event.taskId(), "任务提交失败");
        }
    }
}
