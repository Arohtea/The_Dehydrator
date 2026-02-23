package com.arohtea.business_service.service;

import com.arohtea.business_service.model.AnalysisTask;
import com.arohtea.business_service.model.TaskStatus;
import com.arohtea.business_service.repository.AnalysisTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisService {

    private final AnalysisTaskRepository taskRepository;
    private final RabbitTemplate rabbitTemplate;
    private final StringRedisTemplate redisTemplate;
    private final SystemSettingsService settingsService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AnalysisTask createTask(String documentId, String aiDocId) {
        AnalysisTask task = new AnalysisTask();
        task.setDocumentId(documentId);
        task.setStatus(TaskStatus.PENDING);
        task = taskRepository.save(task);

        // 发送到RabbitMQ异步处理，携带用户配置
        try {
            var settings = settingsService.get();
            ObjectNode msg = objectMapper.createObjectNode();
            msg.put("taskId", task.getId());
            msg.put("docId", aiDocId);
            if (settings.getApiKey() != null) msg.put("apiKey", settings.getApiKey());
            if (settings.getModel() != null) msg.put("model", settings.getModel());
            if (settings.getMapWorkers() != null) msg.put("mapWorkers", settings.getMapWorkers());
            rabbitTemplate.convertAndSend(
                    "analysis.exchange", "analysis.request",
                    objectMapper.writeValueAsString(msg)
            );
        } catch (Exception e) {
            log.error("RabbitMQ发送失败: {}", task.getId(), e);
            task.setStatus(TaskStatus.FAILED);
            task.setCurrentStep("任务提交失败");
            return taskRepository.save(task);
        }

        task.setStatus(TaskStatus.PROCESSING);
        return taskRepository.save(task);
    }

    public AnalysisTask getTask(String taskId) {
        return taskRepository.findById(taskId).orElse(null);
    }

    public List<AnalysisTask> getByDocumentId(String documentId) {
        return taskRepository.findByDocumentId(documentId);
    }

    public AnalysisTask cancelTask(String taskId) {
        AnalysisTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null || task.getStatus() != TaskStatus.PROCESSING) {
            return task;
        }
        redisTemplate.opsForValue().set("analysis:cancel:" + taskId, "1", Duration.ofMinutes(30));
        task.setStatus(TaskStatus.CANCELLED);
        task.setCurrentStep("已取消");
        return taskRepository.save(task);
    }

    @Scheduled(fixedRate = 300_000)
    public void cleanupTimedOutTasks() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(30);
        List<AnalysisTask> stale = taskRepository.findByStatusInAndCreatedAtBefore(
                List.of(TaskStatus.PENDING, TaskStatus.PROCESSING), threshold);
        for (AnalysisTask task : stale) {
            task.setStatus(TaskStatus.FAILED);
            task.setCurrentStep("任务超时");
            task.setCompletedAt(LocalDateTime.now());
            taskRepository.save(task);
            log.info("超时清理任务: {}", task.getId());
        }
    }
}
