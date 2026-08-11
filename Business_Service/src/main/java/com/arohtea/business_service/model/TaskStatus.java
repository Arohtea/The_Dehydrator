package com.arohtea.business_service.model;

/**
 * 分析任务生命周期状态。
 *
 * <p>`CANCELLING` 表示已发出停止请求但尚未收到 AI Service 确认，只有确认后
 * 才能进入 `CANCELLED`。</p>
 */
public enum TaskStatus {
    PENDING, PROCESSING, CANCELLING, COMPLETED, FAILED, CANCELLED
}
