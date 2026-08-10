package com.arohtea.business_service.service;

import com.arohtea.business_service.model.AnalysisTask;
import com.arohtea.business_service.model.TaskStatus;
import com.arohtea.business_service.repository.AnalysisTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** 使用任务行锁执行派发失败和状态迁移，避免取消与结果消息互相覆盖。 */
@Service
@RequiredArgsConstructor
public class AnalysisTaskStateService {

    private final AnalysisTaskRepository taskRepository;
    private final AnalysisStreamService streamService;

    /**
     * 将任务置为处理中；取消中的任务保持原状态。
     *
     * @param taskId 任务 ID
     */
    @Transactional
    public void markProcessing(String taskId) {
        taskRepository.findByIdForUpdate(taskId).ifPresent(task -> {
            if (task.getStatus() == TaskStatus.PENDING) {
                task.setStatus(TaskStatus.PROCESSING);
                task.setCurrentStep("已提交分析任务");
                taskRepository.save(task);
            }
        });
    }

    /**
     * 记录消息投递失败；若任务已进入终止中，则安全收口为已取消。
     *
     * @param taskId 任务 ID
     * @param message 失败说明
     */
    @Transactional
    public void markDispatchFailed(String taskId, String message) {
        taskRepository.findByIdForUpdate(taskId).ifPresent(task -> {
            if (task.getStatus() != TaskStatus.PENDING
                    && task.getStatus() != TaskStatus.PROCESSING
                    && task.getStatus() != TaskStatus.CANCELLING) {
                return;
            }
            if (task.getStatus() == TaskStatus.CANCELLING) {
                task.setStatus(TaskStatus.CANCELLED);
                task.setCurrentStep("已确认终止");
                streamService.publishTerminal(taskId, "CANCELLED", "分析已终止");
            } else {
                task.setStatus(TaskStatus.FAILED);
                task.setCurrentStep(message);
                streamService.publishTerminal(taskId, "FAILED", message);
            }
            task.setCompletedAt(LocalDateTime.now());
            taskRepository.save(task);
        });
    }
}
