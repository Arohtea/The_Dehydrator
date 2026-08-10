package com.arohtea.business_service.service;

import com.arohtea.business_service.model.AnalysisTask;
import com.arohtea.business_service.model.TaskStatus;
import com.arohtea.business_service.repository.AnalysisTaskRepository;
import com.arohtea.business_service.repository.ReferenceLibraryRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${messaging.analysis.exchange}")
    private String analysisExchange;
    @Value("${messaging.analysis.request-queue}")
    private String analysisRequestQueue;
    @Value("${analysis.redis.cancel-prefix}")
    private String cancelPrefix;
    @Value("${analysis.max-concurrent-tasks}")
    private long maxConcurrentTasks;
    @Value("${analysis.task-timeout-minutes}")
    private long taskTimeoutMinutes;
    @Value("${analysis.cancel-ttl-minutes}")
    private long cancelTtlMinutes;

    private String normalizeMode(String mode) {
        return "quick".equalsIgnoreCase(mode) ? "quick" : "deep";
    }

    /**
     * 创建分析任务，并把数据库中的模型配置和深度搜索凭据发送给 AI Service。
     *
     * @param documentId 业务文档 ID
     * @param aiDocId AI Service 中的文档 ID
     * @param mode 分析模式，quick 或 deep
     * @param referenceLibraryIds 参与交叉验证的参考资料集 ID
     * @return 已创建的分析任务
     * @throws IllegalArgumentException 深度分析未配置 Tavily Key 或参考资料集无效
     * @throws IllegalStateException 并发分析任务达到上限
     */
    public synchronized AnalysisTask createTask(
            String documentId,
            String aiDocId,
            String mode,
            List<String> referenceLibraryIds) {
        String normalizedMode = normalizeMode(mode);
        var settings = settingsService.get();
        var textModel = settingsService.requireTextModelConfig(settings);
        var vectorModel = settingsService.requireVectorModelConfig(settings);
        if ("deep".equals(normalizedMode)
                && (settings.getTavilyApiKey() == null || settings.getTavilyApiKey().isBlank())) {
            throw new IllegalArgumentException("深度分析需要先在设置中配置 Tavily API Key");
        }
        List<String> normalizedLibraryIds = normalizeReferenceLibraryIds(referenceLibraryIds);
        if (taskRepository.countByStatusIn(List.of(TaskStatus.PENDING, TaskStatus.PROCESSING))
                >= maxConcurrentTasks) {
            throw new IllegalStateException("当前同时运行的分析任务已达到上限");
        }
        List<String> referenceLibraryNames = resolveReferenceLibraryNames(normalizedLibraryIds);
        AnalysisTask task = new AnalysisTask();
        task.setDocumentId(documentId);
        task.setMode(normalizedMode);
        task.setReferenceLibraryIds(writeJsonList(normalizedLibraryIds));
        task.setReferenceLibraryNames(writeJsonList(referenceLibraryNames));
        task.setStatus(TaskStatus.PROCESSING);
        task = taskRepository.save(task);

        // 发送到RabbitMQ异步处理，携带用户配置
        try {
            ObjectNode msg = objectMapper.createObjectNode();
            msg.put("taskId", task.getId());
            msg.put("docId", aiDocId);
            msg.put("mode", task.getMode());
            msg.set("referenceLibraryIds", objectMapper.valueToTree(normalizedLibraryIds));
            ObjectNode textConfig = msg.putObject("textModel");
            textConfig.put("model", textModel.model());
            textConfig.put("url", textModel.url());
            textConfig.put("apiKey", textModel.apiKey());
            ObjectNode vectorConfig = msg.putObject("vectorModel");
            vectorConfig.put("model", vectorModel.model());
            vectorConfig.put("url", vectorModel.url());
            vectorConfig.put("apiKey", vectorModel.apiKey());
            if ("deep".equals(normalizedMode)) msg.put("tavilyApiKey", settings.getTavilyApiKey());
            if (settings.getMapWorkers() != null) msg.put("mapWorkers", settings.getMapWorkers());
            rabbitTemplate.convertAndSend(
                    analysisExchange, analysisRequestQueue,
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
        redisTemplate.opsForValue().set(cancelPrefix + taskId, "1", Duration.ofMinutes(cancelTtlMinutes));
        task.setStatus(TaskStatus.CANCELLED);
        task.setCurrentStep("已取消");
        return taskRepository.save(task);
    }

    /**
     * 将超过部署超时阈值的未完成任务标记为失败并发布取消信号。
     */
    @Scheduled(fixedRateString = "${analysis.task-cleanup-interval-ms}")
    public void cleanupTimedOutTasks() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(taskTimeoutMinutes);
        List<AnalysisTask> stale = taskRepository.findByStatusInAndCreatedAtBefore(
                List.of(TaskStatus.PENDING, TaskStatus.PROCESSING), threshold);
        for (AnalysisTask task : stale) {
            task.setStatus(TaskStatus.FAILED);
            task.setCurrentStep("任务超时");
            task.setCompletedAt(LocalDateTime.now());
            redisTemplate.opsForValue().set(
                    cancelPrefix + task.getId(),
                    "1",
                    Duration.ofMinutes(cancelTtlMinutes));
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
