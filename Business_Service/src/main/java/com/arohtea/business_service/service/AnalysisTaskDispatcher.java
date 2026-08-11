package com.arohtea.business_service.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 在分析任务记录提交后投递 RabbitMQ，避免 AI 结果先于任务记录落库。
 *
 * <p>创建任务和发送消息故意分成两个阶段：数据库事务负责确认任务确实存在，
 * 事务提交后的监听器负责把已经提交的任务交给 AI Service。发送失败时再通过状态
 * 服务写入失败或取消终态，让前端不会永久停留在“处理中”。</p>
 */
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
     * <p>先把状态从 {@code PENDING} 改为 {@code PROCESSING}，再发送 RabbitMQ 消息。
     * 如果发送失败，统一由状态服务决定是 {@code FAILED} 还是收口已经在取消中的
     * 任务；这里不直接改状态，避免绕过行锁和终态事件。</p>
     *
     * @param event 已提交的任务消息
     */
    @Async("analysisTaskDispatchExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void dispatch(AnalysisTaskCreatedEvent event) {
        // 只有事务提交后才投递，保证 AI Service 收到消息时能查询到对应任务记录。
        // 行锁状态迁移发生在发送前，前端看到 PROCESSING 后才会等待远程处理结果。
        stateService.markProcessing(event.taskId());
        try {
            // exchange 和 routing key 由配置提供，Business Service 不需要知道 AI Service 的具体消费者实现。
            rabbitTemplate.convertAndSend(analysisExchange, analysisRequestQueue, event.message());
            log.info("分析任务已投递: {}", event.taskId());
        } catch (Exception exception) {
            // RabbitMQ 不可用时把任务明确收口，避免数据库里留下永远没有结果的 PROCESSING 任务。
            log.error("RabbitMQ 发送分析任务失败: {}", event.taskId(), exception);
            stateService.markDispatchFailed(event.taskId(), "任务提交失败");
        }
    }
}
