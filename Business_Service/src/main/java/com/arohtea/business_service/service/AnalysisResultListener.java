package com.arohtea.business_service.service;

import com.arohtea.business_service.model.AnalysisTask;
import com.arohtea.business_service.model.TaskStatus;
import com.arohtea.business_service.repository.AnalysisTaskRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 消费 AI Service 的分析结果和进度消息，并以任务行锁更新数据库与 Redis Stream。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisResultListener {

    private static final String EXPECTED_SOURCE = "ai-service";

    private final AnalysisTaskRepository taskRepository;
    private final ObjectMapper objectMapper;
    private final AnalysisStreamService streamService;

    @RabbitListener(queues = "${messaging.analysis.result-queue}")
    @Transactional
    /**
     * 处理单条分析结果消息。
     *
     * @param message AI Service 发布的 JSON 结果、失败或取消确认
     *
     * <p>消息来源、任务 ID 和当前状态都会被校验；取消中的任务拒绝迟到的
     * 成功结果，防止协作式取消被异步消息重新覆盖。</p>
     */
    public void onResult(String message) {
        try {
            JsonNode node = objectMapper.readTree(message);
            if (!EXPECTED_SOURCE.equals(node.path("source").asText())) {
                log.warn("忽略未知来源的分析结果消息");
                return;
            }
            String taskId = node.path("taskId").asText("");
            if (taskId.isBlank()) {
                log.warn("分析结果消息缺少 taskId");
                return;
            }

            AnalysisTask task = taskRepository.findByIdForUpdate(taskId)
                    .orElse(null);
            if (task == null) {
                log.warn("任务不存在: {}", taskId);
                return;
            }

            TaskStatus status = task.getStatus();
            if (node.path("cancelled").asBoolean(false)
                    || "CANCELLED".equalsIgnoreCase(node.path("status").asText())) {
                if (status == TaskStatus.PROCESSING || status == TaskStatus.PENDING
                        || status == TaskStatus.CANCELLING) {
                    task.setStatus(TaskStatus.CANCELLED);
                    task.setCurrentStep("已确认终止");
                    task.setCompletedAt(LocalDateTime.now());
                    taskRepository.save(task);
                    streamService.publishTerminal(taskId, "CANCELLED", "分析已终止");
                }
                return;
            }

            // 终态守卫：取消中的任务不能被迟到的成功或失败消息覆盖。
            if (status != TaskStatus.PROCESSING) {
                log.info("任务已处于状态 {}，跳过更新: {}", status, taskId);
                return;
            }

            // 处理失败消息
            if (node.has("failed") && node.get("failed").asBoolean()) {
                task.setStatus(TaskStatus.FAILED);
                task.setCurrentStep(node.has("error") ? node.get("error").asText().substring(0, Math.min(500, node.get("error").asText().length())) : "分析失败");
                task.setCompletedAt(LocalDateTime.now());
                taskRepository.save(task);
                streamService.publishTerminal(taskId, "FAILED", task.getCurrentStep());
                return;
            }

            if (node.has("argumentChain")) {
                task.setArgumentChain(
                        node.get("argumentChain").toString());
            }
            if (node.has("logicFlaws")) {
                task.setLogicFlaws(
                        node.get("logicFlaws").toString());
            }
            if (node.has("crossValidation")) {
                task.setCrossValidation(
                        node.get("crossValidation").toString());
            }
            if (node.has("mode")) {
                task.setMode(node.get("mode").asText("deep"));
            }
            if (node.has("referenceLibraryIds")) {
                task.setReferenceLibraryIds(node.get("referenceLibraryIds").toString());
            }
            if (node.has("referenceLibraryNames")) {
                task.setReferenceLibraryNames(node.get("referenceLibraryNames").toString());
            }

            task.setProgress(100);
            task.setCurrentStep("quick".equals(task.getMode()) ? "快速分析完成" : "深度分析完成");
            task.setStatus(TaskStatus.COMPLETED);
            task.setCompletedAt(LocalDateTime.now());
            taskRepository.save(task);
            streamService.publishTerminal(taskId, "COMPLETED", task.getCurrentStep());
        } catch (Exception e) {
            log.error("处理分析结果失败", e);
        }
    }

    @RabbitListener(queues = "${messaging.analysis.progress-queue}")
    @Transactional
    /**
     * 处理分析进度消息并同步到任务记录和实时事件流。
     *
     * @param message AI Service 发布的 JSON 进度消息
     */
    public void onProgress(String message) {
        try {
            JsonNode node = objectMapper.readTree(message);
            if (!EXPECTED_SOURCE.equals(node.path("source").asText())) {
                log.warn("忽略未知来源的分析进度消息");
                return;
            }
            String taskId = node.path("taskId").asText("");
            if (taskId.isBlank()) {
                log.warn("分析进度消息缺少 taskId");
                return;
            }
            taskRepository.findByIdForUpdate(taskId).ifPresent(task -> {
                // 只有处理中任务接受进度，CANCELLING 和终态任务的旧进度必须丢弃。
                if (task.getStatus() != TaskStatus.PROCESSING) return;
                int progress = Math.max(0, Math.min(100, node.path("progress").asInt()));
                String currentStep = node.path("currentStep").asText("分析中...");
                task.setProgress(progress);
                task.setCurrentStep(currentStep);
                taskRepository.save(task);
                streamService.publishProgress(taskId, progress, currentStep);
            });
        } catch (Exception e) {
            log.error("处理进度更新失败", e);
        }
    }
}
