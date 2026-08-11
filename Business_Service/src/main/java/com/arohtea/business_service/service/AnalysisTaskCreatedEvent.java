package com.arohtea.business_service.service;

/** 文档行锁事务提交后，由事件监听器投递给 AI Service 的分析任务消息。 */
/**
 * 分析任务事务提交后派发给异步消息监听器的事件。
 *
 * @param taskId 数据库中的任务 ID
 * @param message 已按 AI Service 契约序列化的请求消息
 */
public record AnalysisTaskCreatedEvent(String taskId, String message) {
}
