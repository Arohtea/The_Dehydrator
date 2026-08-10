package com.arohtea.business_service.service;

/** 文档行锁事务提交后，由事件监听器投递给 AI Service 的分析任务消息。 */
public record AnalysisTaskCreatedEvent(String taskId, String message) {
}
