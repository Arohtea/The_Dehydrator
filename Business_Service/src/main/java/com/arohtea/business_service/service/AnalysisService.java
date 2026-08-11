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

/**
 * 分析任务的创建、取消、超时清理和删除协同服务。
 *
 * <p>任务状态与文档删除状态分别保存在数据库和 Redis 中，数据库行锁负责
 * 串行化竞争请求，Redis 取消键负责把停止信号传递给 AI Service。</p>
 */
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

    /**
     * 将外部模式值收敛到系统支持的两个模式。
     *
     * @param mode 请求传入的模式，可能为空或大小写不一致
     * @return `quick` 或 `deep`；除 quick 外的值统一按 deep 处理
     */
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

        // 文档行锁和活动任务检查共同保证同一文档不会并发创建多个分析任务。
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

        // 把资料库名称保存为快照，避免资料库重命名后历史任务结果失去原始语义。
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

    /**
     * 按任务 ID 查询当前状态和已持久化结果。
     *
     * @param taskId 分析任务 ID
     * @return 任务实体；不存在时返回 null
     */
    public AnalysisTask getTask(String taskId) {
        return taskRepository.findById(taskId).orElse(null);
    }

    /**
     * 查询文档的全部历史任务并按创建时间升序返回。
     *
     * @param documentId 业务文档 ID
     * @return 该文档的历史分析任务
     */
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
        // 删除流程只标记文档并发出取消信号，真正删除要等所有活动任务离开活动态。
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
    /**
     * 在有限等待窗口内轮询文档活动任务是否全部收口。
     *
     * @param documentId 文档 ID
     * @return 在截止时间前活动任务为空时返回 true；线程被中断或仍有活动任务
     *         时返回 false
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
    /**
     * 将超过超时阈值的 PENDING/PROCESSING 任务转为 CANCELLING。
     *
     * 超时任务仍需等待 AI Service 确认，不能直接写成 CANCELLED，否则可能把
     * 仍在运行的外部模型调用误认为已经停止。
     */
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

    /**
     * 写入取消信号并把可取消状态迁移到 CANCELLING。
     *
     * @param task 已在当前事务中读取的任务实体
     */
    private void requestCancellation(AnalysisTask task) {
        requestCancellationSignal(task.getId());
        if (task.getStatus() == TaskStatus.PENDING || task.getStatus() == TaskStatus.PROCESSING) {
            task.setStatus(TaskStatus.CANCELLING);
            task.setCurrentStep("正在终止分析");
        }
    }

    /**
     * 写入带 TTL 的协作式取消键，供 AI Service 在流式调用中定期读取。
     *
     * @param taskId 分析任务 ID
     */
    private void requestCancellationSignal(String taskId) {
        redisTemplate.opsForValue().set(
                cancelPrefix + taskId, "1", Duration.ofMinutes(cancelTtlMinutes));
    }

    /**
     * 组装 RabbitMQ 分析请求的跨服务 JSON 消息。
     *
     * @param task 任务实体
     * @param document 业务文档实体
     * @param referenceLibraryIds 清洗后的参考资料库 ID
     * @param textModel 文本模型配置快照
     * @param vectorModel 向量模型配置快照
     * @param settings 上传/创建任务时读取的系统设置快照
     * @return 可直接发送给 AI Service 的 JSON 字符串
     * @throws IllegalStateException 配置无法序列化时抛出
     */
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

    /**
     * 清洗资料库 ID，去掉空值、首尾空白和重复项。
     *
     * @param referenceLibraryIds 原始请求列表
     * @return 稳定顺序的去重 ID 列表
     */
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

    /**
     * 按 ID 查询资料库名称并验证所有 ID 都真实存在。
     *
     * @param referenceLibraryIds 已清洗的资料库 ID
     * @return 与输入 ID 顺序一致的名称快照
     * @throws IllegalArgumentException 任一资料库不存在
     */
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

    /**
     * 把列表持久化为实体字段使用的 JSON 文本。
     *
     * @param values 待序列化列表
     * @return JSON 数组文本
     * @throws IllegalStateException 序列化失败
     */
    private String writeJsonList(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("序列化资料集信息失败", exception);
        }
    }
}
