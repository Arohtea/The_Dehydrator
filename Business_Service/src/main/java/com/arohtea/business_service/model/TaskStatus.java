package com.arohtea.business_service.model;

/**
 * 分析任务生命周期状态。
 *
 * <p>`CANCELLING` 表示已发出停止请求但尚未收到 AI Service 确认，只有确认后
 * 才能进入 `CANCELLED`。</p>
 */
public enum TaskStatus {
    /** 已创建但事务后消息尚未完成派发。 */
    PENDING,
    /** AI Service 已收到任务，正在执行分析。 */
    PROCESSING,
    /** 已发出取消信号，等待 AI Service 确认停止。 */
    CANCELLING,
    /** AI Service 返回了完整分析结果。 */
    COMPLETED,
    /** 派发、处理或结果回写过程中发生不可恢复错误。 */
    FAILED,
    /** 已收到取消确认，任务不再运行。 */
    CANCELLED
}
