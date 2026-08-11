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
 * <p>一次分析会同时经过三个系统：数据库保存任务状态和最终结果，RabbitMQ
 * 负责把任务交给 AI Service，Redis 保存取消信号和供前端回放的实时事件。这里
 * 负责把三者串起来，并用数据库行锁保证“启动、取消、超时、结果回写”不会同时
 * 修改同一条任务记录。</p>
 *
 * <p>任务创建时只先写入 {@code PENDING}，事务提交后才发送消息；AI Service
 * 确认收到并开始处理后才进入 {@code PROCESSING}。取消也不是立即写成最终状态，
 * 而是先进入 {@code CANCELLING}，等待外部服务确认后才进入 {@code CANCELLED}，
 * 这样不会把仍在运行的模型调用误认为已经停止。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisService {

    /** 仍然占用分析名额，且不允许文档被彻底删除的状态。 */
    private static final List<TaskStatus> ACTIVE_STATUSES = List.of(
            TaskStatus.PENDING, TaskStatus.PROCESSING, TaskStatus.CANCELLING);

    /** 可能因长时间没有结果而被定时任务判定为超时的状态。 */
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
     * <p>前端可以不传模式，历史客户端也可能传入未知值。为了保持兼容，只有明确
     * 写成 {@code quick} 时才走快速分析，其他情况统一按功能更完整的深度分析处理。</p>
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
     * <p>处理顺序对应用户看到的“开始分析”动作：先锁定文档并确认文档可用，再
     * 检查同一文档和全局的并发限制，随后冻结本次任务所需的模型、参考资料库名称
     * 和搜索配置。最后才保存任务并发布领域事件；事件监听器会在事务提交后将消息
     * 发给 AI Service。</p>
     *
     * <p>配置被复制到消息中而不是让 AI Service 临时读取，是为了保证用户在分析
     * 过程中修改设置时，正在运行的任务仍使用创建时的完整配置。</p>
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
        // 先锁文档，和删除流程使用同一把数据库行锁，避免“刚检查完可用就被删除”。
        Document document = documentRepository.findByIdForUpdate(documentId).orElse(null);
        if (document == null) {
            throw new IllegalArgumentException("文档不存在");
        }
        if (document.isDeleting()) {
            throw new AnalysisConflictException("文档正在删除，不能启动分析");
        }
        // 没有 AI 文档 ID 说明后台向量化尚未完成；没有向量就无法被分析模型检索。
        if (document.getAiDocId() == null || document.getAiDocId().isBlank()) {
            throw new IllegalStateException("文档正在向量化，请稍后再试");
        }

        // 文档行锁挡住同一文档的竞争请求，活动任务检查挡住已经存在的历史任务。
        // 两层检查一起保证同一文档不会因为重复点击而创建多个并行分析。
        List<AnalysisTask> documentTasks = taskRepository.findByDocumentIdAndStatusIn(
                documentId, ACTIVE_STATUSES);
        if (!documentTasks.isEmpty()) {
            throw new AnalysisConflictException("该文档已有分析任务正在运行");
        }
        // 这是全局上限，不按用户区分；模型调用成本和机器资源由整个服务共同承担。
        if (taskRepository.countByStatusIn(ACTIVE_STATUSES) >= maxConcurrentTasks) {
            throw new IllegalStateException("当前同时运行的分析任务已达到上限");
        }

        // 读取一次设置并在本次任务中复用，避免文本模型、向量模型和搜索 Key 来自不同时间点。
        String normalizedMode = normalizeMode(mode);
        var settings = settingsService.get();
        var textModel = settingsService.requireTextModelConfig(settings);
        var vectorModel = settingsService.requireVectorModelConfig(settings);
        if ("deep".equals(normalizedMode)
                && (settings.getTavilyApiKey() == null || settings.getTavilyApiKey().isBlank())) {
            throw new IllegalArgumentException("深度分析需要先在设置中配置 Tavily API Key");
        }

        // 先清洗 ID，再查询名称；查询名称既验证 ID 存在，也为历史任务保存可读快照。
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
        // 必须先把任务真正写入数据库，事务提交后的消息消费者才能查到它。
        task = taskRepository.saveAndFlush(task);

        // 事件不是立即发送 RabbitMQ，而是交给 AFTER_COMMIT 监听器处理，避免事务回滚后
        // AI Service 仍收到一条数据库中不存在的任务消息。
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
     * <p>取消请求只改变任务的协调状态，不直接终止远程 HTTP/模型调用。AI Service
     * 会在处理过程中读取 Redis 取消键，完成清理后通过 RabbitMQ 回传确认；结果监听器
     * 再把任务收口为最终的 {@code CANCELLED}。</p>
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
        // 已完成、已失败或已取消的任务没有远程工作需要停止，重复取消直接返回当前状态。
        if (!ACTIVE_STATUSES.contains(task.getStatus())) {
            return task;
        }
        requestCancellation(task);
        return taskRepository.save(task);
    }

    /**
     * 标记文档进入删除流程，并为该文档所有活动任务写入取消信号。
     *
     * <p>设置 {@code deleting=true} 是删除流程的闸门：后续启动分析和迟到的向量回写
     * 都会被拒绝。之后锁定该文档的活动任务并逐个发出取消信号，确保删除和任务状态
     * 迁移按照数据库顺序执行。</p>
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
     * 在有限等待窗口内轮询文档活动任务是否全部收口。
     *
     * <p>活动任务从查询结果中消失，表示结果监听器已经收到取消确认或消息投递失败
     * 已被安全收口。只有确认收口后，调用方才可以删除数据库、对象存储和向量；否则
     * 删除会留下仍在运行的远程任务和无法回收的资源。</p>
     *
     * @param documentId 文档 ID
     * @return 在截止时间前活动任务为空时返回 true；线程被中断或仍有活动任务时返回 false
     */
    public boolean awaitCancellation(String documentId) {
        long deadline = System.nanoTime() + Duration.ofMillis(cancelWaitMs).toNanos();
        while (System.nanoTime() < deadline) {
            if (taskRepository.findByDocumentIdAndStatusIn(documentId, ACTIVE_STATUSES).isEmpty()) {
                return true;
            }
            // 数据库是取消确认的事实来源，短暂轮询可以避免删除请求一直占用事务连接。
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
     * <p>先删除并 flush 数据库任务记录，再删除 Redis 中的取消键和 Stream。这样
     * 后续查询不会再看到历史任务，同时也不会保留已经删除文档的实时事件。</p>
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
     * 定期收口超过超时阈值的 {@code PENDING}/{@code PROCESSING} 任务。
     *
     * <p>第一次查询只找出候选任务，真正更新前会重新加行锁并再次检查状态，因为
     * 候选列表生成后，结果消息可能已经把任务改成终态。超时只代表 Business Service
     * 没有在预期时间内看到结果，不代表远程模型已经停止，所以这里只发送取消信号并
     * 写成 {@code CANCELLING}，不能直接写成 {@code CANCELLED}。</p>
     */
    @Scheduled(fixedRateString = "${analysis.task-cleanup-interval-ms}")
    @Transactional
    public void cleanupTimedOutTasks() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(taskTimeoutMinutes);
        List<AnalysisTask> stale = taskRepository.findByStatusInAndCreatedAtBefore(RUNNING_STATUSES, threshold);
        for (AnalysisTask candidate : stale) {
            // 重新加锁是为了防止定时清理覆盖刚刚到达的成功、失败或取消确认消息。
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
     * 写入取消信号并把可取消状态迁移到 {@code CANCELLING}。
     *
     * <p>Redis 键负责通知 AI Service，数据库状态负责通知前端和其他 Business Service
     * 请求。两者必须在同一次业务操作中更新，才能让“正在终止”在各个入口保持一致。</p>
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
     * <p>TTL 防止服务异常退出后留下永久取消标记；键值本身只表达“需要停止”，
     * 不携带业务内容，具体取消哪个任务由键名中的任务 ID 确定。</p>
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
     * <p>消息把一次任务所需的输入和配置完整打包：AI 文档 ID 用于检索向量，文本/向量
     * 模型用于调用外部模型，参考资料库 ID 用于交叉验证。深度模式才携带 Tavily Key，
     * 因此快速模式不会无谓地传递联网搜索凭据。</p>
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
            // Key 只存在于跨服务内部消息中，日志和对外响应都不打印这段 JSON。
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
