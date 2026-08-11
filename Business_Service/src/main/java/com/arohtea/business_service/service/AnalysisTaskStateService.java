package com.arohtea.business_service.service;

import com.arohtea.business_service.model.AnalysisTask;
import com.arohtea.business_service.model.TaskStatus;
import com.arohtea.business_service.repository.AnalysisTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 使用任务行锁执行派发失败和状态迁移，避免取消与结果消息互相覆盖。
 *
 * <p>任务状态不只是页面显示，它还决定文档能否删除、是否占用并发名额以及是否
 * 接受 AI Service 的结果。因此所有跨线程状态迁移都集中到这里，并在保存状态时
 * 生成对应的终态事件。</p>
 */
@Service
@RequiredArgsConstructor
public class AnalysisTaskStateService {

    private final AnalysisTaskRepository taskRepository;
    private final AnalysisStreamService streamService;

    /**
     * 将任务置为处理中；取消中的任务保持原状态。
     *
     * <p>消息派发线程可能和用户取消线程同时运行。只有锁内仍为 {@code PENDING}
     * 才能推进到 {@code PROCESSING}；如果取消已经先拿到锁，状态会保持
     * {@code CANCELLING}，从而阻止派发逻辑覆盖用户的意图。</p>
     *
     * @param taskId 任务 ID
     */
    @Transactional
    public void markProcessing(String taskId) {
        // 行锁让取消请求与派发状态迁移按数据库顺序执行，避免互相覆盖。
        taskRepository.findByIdForUpdate(taskId).ifPresent(task -> {
            // 只有刚创建、尚未投递的任务需要推进；其他状态说明别的流程已经先处理了它。
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
     * <p>发送失败和取消请求可能前后到达，所以方法先重新加锁并只接受活动状态。
     * 取消中的任务不再显示“派发失败”，而是视为停止请求已经没有待处理的远程工作，
     * 直接发送 {@code CANCELLED}；普通活动任务才标记 {@code FAILED}。</p>
     *
     * @param taskId 任务 ID
     * @param message 失败说明
     */
    @Transactional
    public void markDispatchFailed(String taskId, String message) {
        // 读取、判断、写入和终态事件都在同一事务中完成，避免产生半套状态。
        taskRepository.findByIdForUpdate(taskId).ifPresent(task -> {
            // 终态任务可能已经收到 AI Service 结果，不能被更晚到达的发送异常覆盖。
            if (task.getStatus() != TaskStatus.PENDING
                    && task.getStatus() != TaskStatus.PROCESSING
                    && task.getStatus() != TaskStatus.CANCELLING) {
                return;
            }
            if (task.getStatus() == TaskStatus.CANCELLING) {
                // 用户已经请求取消，此时投递失败不会再有远程任务需要等待，安全收口为取消。
                task.setStatus(TaskStatus.CANCELLED);
                task.setCurrentStep("已确认终止");
                streamService.publishTerminal(taskId, "CANCELLED", "分析已终止");
            } else {
                // 任务既没有完成也没有取消，发送失败就是本次分析的最终失败原因。
                task.setStatus(TaskStatus.FAILED);
                task.setCurrentStep(message);
                streamService.publishTerminal(taskId, "FAILED", message);
            }
            // 两种终态都记录结束时间，前端和后续清理可以据此判断任务已结束。
            task.setCompletedAt(LocalDateTime.now());
            taskRepository.save(task);
        });
    }
}
