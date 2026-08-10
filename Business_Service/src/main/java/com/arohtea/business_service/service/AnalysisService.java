package com.arohtea.business_service.service;

import com.arohtea.business_service.model.AnalysisTask;
import com.arohtea.business_service.model.TaskStatus;
import com.arohtea.business_service.repository.AnalysisTaskRepository;
import com.arohtea.business_service.repository.ReferenceLibraryRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
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
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisService {

    private final AnalysisTaskRepository taskRepository;
    private final RabbitTemplate rabbitTemplate;
    private final StringRedisTemplate redisTemplate;
    private final SystemSettingsService settingsService;
    private final ReferenceLibraryRepository referenceLibraryRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private String normalizeMode(String mode) {
        return "quick".equalsIgnoreCase(mode) ? "quick" : "deep";
    }

    public synchronized AnalysisTask createTask(
            String documentId,
            String aiDocId,
            String mode,
            List<String> referenceLibraryIds) {
        List<String> normalizedLibraryIds = normalizeReferenceLibraryIds(referenceLibraryIds);
        if (taskRepository.countByStatusIn(List.of(TaskStatus.PENDING, TaskStatus.PROCESSING)) >= 2) {
            throw new IllegalStateException("当前同时运行的分析任务已达到上限");
        }
        List<String> referenceLibraryNames = resolveReferenceLibraryNames(normalizedLibraryIds);
        AnalysisTask task = new AnalysisTask();
        task.setDocumentId(documentId);
        task.setMode(normalizeMode(mode));
        task.setReferenceLibraryIds(writeJsonList(normalizedLibraryIds));
        task.setReferenceLibraryNames(writeJsonList(referenceLibraryNames));
        task.setStatus(TaskStatus.PROCESSING);
        task = taskRepository.save(task);

        // 发送到RabbitMQ异步处理，携带用户配置
        try {
            var settings = settingsService.get();
            ObjectNode msg = objectMapper.createObjectNode();
            msg.put("taskId", task.getId());
            msg.put("docId", aiDocId);
            msg.put("mode", task.getMode());
            msg.set("referenceLibraryIds", objectMapper.valueToTree(normalizedLibraryIds));
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

        return task;
    }

    public AnalysisTask getTask(String taskId) {
        return taskRepository.findById(taskId).orElse(null);
    }

    public List<AnalysisTask> getByDocumentId(String documentId) {
        return taskRepository.findByDocumentIdOrderByCreatedAtAsc(documentId);
    }

    public AnalysisTask cancelTask(String taskId) {
        AnalysisTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null || (task.getStatus() != TaskStatus.PROCESSING && task.getStatus() != TaskStatus.PENDING)) {
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
            redisTemplate.opsForValue().set("analysis:cancel:" + task.getId(), "1", Duration.ofMinutes(30));
            taskRepository.save(task);
            log.info("超时清理任务: {}", task.getId());
        }
    }

    private List<String> normalizeReferenceLibraryIds(List<String> referenceLibraryIds) {
        if (referenceLibraryIds == null || referenceLibraryIds.isEmpty()) {
            return List.of();
        }
        return referenceLibraryIds.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(id -> !id.isEmpty())
                .distinct()
                .toList();
    }

    private List<String> resolveReferenceLibraryNames(List<String> referenceLibraryIds) {
        if (referenceLibraryIds.isEmpty()) {
            return List.of();
        }
        var libraries = referenceLibraryRepository.findAllById(referenceLibraryIds).stream()
                .collect(Collectors.toMap(
                        library -> library.getId(),
                        library -> library.getName()
                ));
        if (libraries.size() != referenceLibraryIds.size()) {
            throw new IllegalArgumentException("参考资料集不存在");
        }
        return referenceLibraryIds.stream()
                .map(libraries::get)
                .filter(Objects::nonNull)
                .toList();
    }

    private String writeJsonList(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化资料集信息失败", e);
        }
    }
}
