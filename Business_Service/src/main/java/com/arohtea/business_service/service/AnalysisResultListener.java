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

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisResultListener {

    private final AnalysisTaskRepository taskRepository;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = "analysis.result")
    public void onResult(String message) {
        try {
            JsonNode node = objectMapper.readTree(message);
            String taskId = node.get("taskId").asText();

            AnalysisTask task = taskRepository.findById(taskId)
                    .orElse(null);
            if (task == null) {
                log.warn("任务不存在: {}", taskId);
                return;
            }

            // 终态守卫：已完成/失败/取消的任务不再更新
            TaskStatus status = task.getStatus();
            if (status == TaskStatus.COMPLETED || status == TaskStatus.FAILED || status == TaskStatus.CANCELLED) {
                log.info("任务已处于终态 {}，跳过更新: {}", status, taskId);
                return;
            }

            // 处理失败消息
            if (node.has("failed") && node.get("failed").asBoolean()) {
                task.setStatus(TaskStatus.FAILED);
                task.setCurrentStep(node.has("error") ? node.get("error").asText() : "分析失败");
                task.setCompletedAt(LocalDateTime.now());
                taskRepository.save(task);
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

            task.setProgress(100);
            task.setCurrentStep("分析完成");
            task.setStatus(TaskStatus.COMPLETED);
            task.setCompletedAt(LocalDateTime.now());
            taskRepository.save(task);
        } catch (Exception e) {
            log.error("处理分析结果失败", e);
        }
    }

    @RabbitListener(queues = "analysis.progress")
    public void onProgress(String message) {
        try {
            JsonNode node = objectMapper.readTree(message);
            String taskId = node.get("taskId").asText();
            taskRepository.findById(taskId).ifPresent(task -> {
                if (task.getStatus() != TaskStatus.PROCESSING) return;
                task.setProgress(node.get("progress").asInt());
                task.setCurrentStep(node.get("currentStep").asText());
                taskRepository.save(task);
            });
        } catch (Exception e) {
            log.error("处理进度更新失败", e);
        }
    }
}
