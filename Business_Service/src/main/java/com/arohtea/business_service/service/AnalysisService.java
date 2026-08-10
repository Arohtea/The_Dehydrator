package com.arohtea.business_service.service;

import com.arohtea.business_service.model.AnalysisConflictException;
import com.arohtea.business_service.model.AnalysisTask;
import com.arohtea.business_service.model.Document;
import com.arohtea.business_service.model.TaskStatus;
import com.arohtea.business_service.repository.AnalysisTaskRepository;
import com.arohtea.business_service.repository.DocumentRepository;
import com.arohtea.business_service.repository.ReferenceLibraryRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisService {

    private static final List<TaskStatus> ACTIVE_STATUSES = List.of(
            TaskStatus.PENDING, TaskStatus.PROCESSING, TaskStatus.CANCELLING);
    private static final List<TaskStatus> RUNNING_STATUSES = List.of(
            TaskStatus.PENDING, TaskStatus.PROCESSING);

    private final AnalysisTaskRepository taskRepository;
    private final DocumentRepository documentRepository;
    private final StringRedisTemplate redisTemplate;
    private final SystemSettingsService settingsService;
    private final ReferenceLibraryRepository referenceLibraryRepository;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final AnalysisStreamService streamService;

    @Value("${analysis.redis.cancel-prefix}")
    private String cancelPrefix;
    @Value("${analysis.max-concurrent-tasks}")
    private long maxConcurrentTasks;
    @Value("${analysis.task-timeout-minutes}")
    private long taskTimeoutMinutes;
    @Value("${analysis.cancel-ttl-minutes}")
    private long cancelTtlMinutes;
    @Value("${analysis.cancel-wait-ms:90000}")
    private long cancelWaitMs;

    private String normalizeMode(String mode) {
        return "quick".equalsIgnoreCase(mode) ? "quick" : "deep";
    }

    /**
     * 在文档行锁保护下创建分析任务，并在事务提交后投递消息。
     *
     * @param documentId 业务文档 ID
     * @param mode 分析模式，quick 或 deep
     * @param referenceLibraryIds 参与交叉验证的参考资料集 ID
     * @return 已创建的分析任务
     * @throws IllegalArgumentException 深度分析未配置 Tavily Key 或参考资料集无效
     * @throws IllegalStateException 并发分析任务达到上限或文档未完成向量化
     * @throws AnalysisConflictException 文档已有活动任务或正在删除
     */
    @Transactional
    public AnalysisTask createTask(String documentId, String mode, List<String> referenceLibraryIds) {
        Document document = documentRepository.findByIdForUpdate(documentId).orElse(null);
        if (document == null) {
            throw new IllegalArgumentException("文档不存在");
        }
        if (document.isDeleting()) {
            throw new AnalysisConflictException("文档正在删除，不能启动分析");
        }
        if (document.getAiDocId() == null || document.getAiDocId().isBlank()) {
            throw new IllegalStateException("文档正在向量化，请稍后再试");
        }

        List<AnalysisTask> documentTasks = taskRepository.findByDocumentIdAndStatusIn(
                documentId, ACTIVE_STATUSES);
        if (!documentTasks.isEmpty()) {
            throw new AnalysisConflictException("该文档已有分析任务正在运行");
        }
        if (taskRepository.countByStatusIn(ACTIVE_STATUSES) >= maxConcurrentTasks) {
            throw new IllegalStateException("当前同时运行的分析任务已达到上限");
        }

        String normalizedMode = normalizeMode(mode);
        var settings = settingsService.get();
        var textModel = settingsService.requireTextModelConfig(settings);
        var vectorModel = settingsService.requireVectorModelConfig(settings);
        if ("deep".equals(normalizedMode)
                && (settings.getTavilyApiKey() == null || settings.getTavilyApiKey().isBlank())) {
            throw new IllegalArgumentException("深度分析需要先在设置中配置 Tavily API Key");
        }

        List<String> normalizedLibraryIds = normalizeReferenceLibraryIds(referenceLibraryIds);
        List<String> referenceLibraryNames = resolveReferenceLibraryNames(normalizedLibraryIds);

        AnalysisTask task = new AnalysisTask();
        task.setDocumentId(documentId);
        task.setMode(normalizedMode);
        task.setReferenceLibraryIds(writeJsonList(normalizedLibraryIds));
        task.setReferenceLibraryNames(writeJsonList(referenceLibraryNames));
        task.setStatus(TaskStatus.PENDING);
        task.setCurrentStep("等待提交分析任务");
        task = taskRepository.saveAndFlush(task);

        eventPublisher.publishEvent(new AnalysisTaskCreatedEvent(
                task.getId(),
                buildRequestMessage(task, document, normalizedLibraryIds, textModel, vectorModel, settings)
        ));
        return task;
    }

    public AnalysisTask getTask(String taskId) {
        return taskRepository.findById(taskId).orElse(null);
    }

    public List<AnalysisTask> getByDocumentId(String documentId) {
        return taskRepository.findByDocumentIdOrderByCreatedAtAsc(documentId);
    }

    /**
     * 写入协作式取消信号并进入终止中状态，只有 AI Service 确认后才进入 CANCELLED。
     *
     * @param taskId 任务 ID
     * @return 当前任务，不存在时返回 null
     */
    @Transactional
    public AnalysisTask cancelTask(String taskId) {
        AnalysisTask task = taskRepository.findByIdForUpdate(taskId).orElse(null);
        if (task == null) {
            return null;
        }
        if (!ACTIVE_STATUSES.contains(task.getStatus())) {
            return task;
        }
        requestCancellation(task);
        return taskRepository.save(task);
    }

    /**
     * 标记文档进入删除流程，并为该文档所有活动任务写入取消信号。
     *
     * @param documentId 文档 ID
     * @return 文档存在时返回 true，不存在时返回 false
     */
    @Transactional
    public boolean beginDocumentDeletion(String documentId) {
        Document document = documentRepository.findByIdForUpdate(documentId).orElse(null);
        if (document == null) {
            return false;
        }
        document.setDeleting(true);
        documentRepository.save(document);
        taskRepository.findByDocumentIdAndStatusInForUpdate(documentId, ACTIVE_STATUSES)
                .forEach(task -> {
                    requestCancellation(task);
                    taskRepository.save(task);
                });
        return true;
    }

    /**
     * 等待 AI Service 对文档下所有活动任务发送取消确认。
     *
     * @param documentId 文档 ID
     * @return 在等待上限内所有任务都已离开活动状态时返回 true
     */
    public boolean awaitCancellation(String documentId) {
        long deadline = System.nanoTime() + Duration.ofMillis(cancelWaitMs).toNanos();
        while (System.nanoTime() < deadline) {
            if (taskRepository.findByDocumentIdAndStatusIn(documentId, ACTIVE_STATUSES).isEmpty()) {
                return true;
            }
            try {
                Thread.sleep(250L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return taskRepository.findByDocumentIdAndStatusIn(documentId, ACTIVE_STATUSES).isEmpty();
    }

    /**
     * 删除文档成功后清理该文档的历史任务、取消键和事件流。
     *
     * @param documentId 文档 ID
     */
    @Transactional
    public void removeTasksForDeletedDocument(String documentId) {
        List<AnalysisTask> tasks = taskRepository.findByDocumentIdOrderByCreatedAtAsc(documentId);
        taskRepository.deleteByDocumentId(documentId);
        taskRepository.flush();
        tasks.forEach(task -> {
            streamService.delete(task.getId());
            redisTemplate.delete(cancelPrefix + task.getId());
        });
    }

    /**
     * 将超过部署超时阈值的未完成任务置为终止中并发布取消信号。
     */
    @Scheduled(fixedRateString = "${analysis.task-cleanup-interval-ms}")
    @Transactional
    public void cleanupTimedOutTasks() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(taskTimeoutMinutes);
        List<AnalysisTask> stale = taskRepository.findByStatusInAndCreatedAtBefore(RUNNING_STATUSES, threshold);
        for (AnalysisTask candidate : stale) {
            AnalysisTask task = taskRepository.findByIdForUpdate(candidate.getId()).orElse(null);
            if (task == null) {
                continue;
            }
            if (!RUNNING_STATUSES.contains(task.getStatus())) {
                continue;
            }
            requestCancellationSignal(task.getId());
            task.setStatus(TaskStatus.CANCELLING);
            task.setCurrentStep("任务超时，正在终止");
            taskRepository.save(task);
            log.info("超时清理任务: {}", task.getId());
        }
    }

    private void requestCancellation(AnalysisTask task) {
        requestCancellationSignal(task.getId());
        if (task.getStatus() == TaskStatus.PENDING || task.getStatus() == TaskStatus.PROCESSING) {
            task.setStatus(TaskStatus.CANCELLING);
            task.setCurrentStep("正在终止分析");
        }
    }

    private void requestCancellationSignal(String taskId) {
        redisTemplate.opsForValue().set(
                cancelPrefix + taskId, "1", Duration.ofMinutes(cancelTtlMinutes));
    }

    private String buildRequestMessage(
            AnalysisTask task,
            Document document,
            List<String> referenceLibraryIds,
            com.arohtea.business_service.model.AiModelConfig textModel,
            com.arohtea.business_service.model.AiModelConfig vectorModel,
            com.arohtea.business_service.model.SystemSettings settings) {
        try {
            ObjectNode msg = objectMapper.createObjectNode();
            msg.put("taskId", task.getId());
            msg.put("docId", document.getAiDocId());
            msg.put("mode", task.getMode());
            msg.set("referenceLibraryIds", objectMapper.valueToTree(referenceLibraryIds));
            ObjectNode textConfig = msg.putObject("textModel");
            textConfig.put("model", textModel.model());
            textConfig.put("url", textModel.url());
            textConfig.put("apiKey", textModel.apiKey());
            ObjectNode vectorConfig = msg.putObject("vectorModel");
            vectorConfig.put("model", vectorModel.model());
            vectorConfig.put("url", vectorModel.url());
            vectorConfig.put("apiKey", vectorModel.apiKey());
            if ("deep".equals(task.getMode())) msg.put("tavilyApiKey", settings.getTavilyApiKey());
            if (settings.getMapWorkers() != null) msg.put("mapWorkers", settings.getMapWorkers());
            return objectMapper.writeValueAsString(msg);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("序列化分析任务失败", exception);
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
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("序列化资料集信息失败", exception);
        }
    }
}
