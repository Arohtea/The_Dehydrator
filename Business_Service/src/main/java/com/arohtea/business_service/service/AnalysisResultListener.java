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
 *
 * <p>RabbitMQ 消息是异步到达的，可能晚于用户的取消请求，也可能在任务已经进入
 * 终态后才到达。因此这里不能“收到什么就写什么”，而要先确认消息来源、任务存在
 * 且当前状态允许被更新，再同时更新数据库和前端事件流。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisResultListener {

    private static final String EXPECTED_SOURCE = "ai-service";

    private final AnalysisTaskRepository taskRepository;
    private final ObjectMapper objectMapper;
    private final AnalysisStreamService streamService;

    /**
     * 处理单条分析结果消息。
     *
     * <p>处理顺序是：解析并验证来源 -> 锁定任务 -> 优先处理取消确认 -> 拒绝不再
     * 接受结果的状态 -> 保存失败或成功结果 -> 推送终态事件。这样迟到的成功消息
     * 不会把已经取消的任务重新显示成完成。</p>
     *
     * @param message AI Service 发布的 JSON 结果、失败或取消确认
     */
    @RabbitListener(queues = "${messaging.analysis.result-queue}")
    @Transactional
    public void onResult(String message) {
        try {
            // 先把文本解析成 JSON；格式错误时由 catch 记录日志，不能让消费者线程直接崩溃。
            JsonNode node = objectMapper.readTree(message);
            // 只接受 AI Service 发出的消息，避免其他生产者误改本服务的任务状态。
            if (!EXPECTED_SOURCE.equals(node.path("source").asText())) {
                log.warn("忽略未知来源的分析结果消息");
                return;
            }
            // taskId 是消息和数据库任务之间的关联键，没有它就无法安全回写。
            String taskId = node.path("taskId").asText("");
            if (taskId.isBlank()) {
                log.warn("分析结果消息缺少 taskId");
                return;
            }

            // 状态判断和后续写入必须在同一把行锁内完成，防止取消请求同时修改这条任务。
            AnalysisTask task = taskRepository.findByIdForUpdate(taskId)
                    .orElse(null);
            if (task == null) {
                log.warn("任务不存在: {}", taskId);
                return;
            }

            TaskStatus status = task.getStatus();
            // 取消确认优先于普通结果处理；只有仍处于活动态的任务才需要再次收口。
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

            // 终态守卫：取消中的任务不能被迟到的成功或失败消息覆盖；只有 PROCESSING
            // 才代表 AI Service 当前仍被允许提交普通结果。
            if (status != TaskStatus.PROCESSING) {
                log.info("任务已处于状态 {}，跳过更新: {}", status, taskId);
                return;
            }

            // 失败消息没有结构化分析结果，保存有限长度的错误文本，避免异常内容撑爆字段。
            if (node.has("failed") && node.get("failed").asBoolean()) {
                task.setStatus(TaskStatus.FAILED);
                task.setCurrentStep(node.has("error") ? node.get("error").asText().substring(0, Math.min(500, node.get("error").asText().length())) : "分析失败");
                task.setCompletedAt(LocalDateTime.now());
                taskRepository.save(task);
                streamService.publishTerminal(taskId, "FAILED", task.getCurrentStep());
                return;
            }

            // 成功消息中的每个结果块都是可选的，按消息实际包含的字段回写，兼容不同分析模式。
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

            // 所有结果字段写入后才宣布 100%，并发送唯一的 completed 终态事件通知前端收口。
            task.setProgress(100);
            task.setCurrentStep("quick".equals(task.getMode()) ? "快速分析完成" : "深度分析完成");
            task.setStatus(TaskStatus.COMPLETED);
            task.setCompletedAt(LocalDateTime.now());
            taskRepository.save(task);
            streamService.publishTerminal(taskId, "COMPLETED", task.getCurrentStep());
        } catch (Exception e) {
            // 消费端记录坏消息和回写异常；具体消息由 RabbitMQ 的容器确认策略决定后续处理。
            log.error("处理分析结果失败", e);
        }
    }

    /**
     * 处理分析进度消息并同步到任务记录和实时事件流。
     *
     * <p>进度只是处理中任务的临时展示信息，不参与最终结果判定。收到进度时先锁定
     * 任务，再丢弃取消中或已结束任务的旧进度，最后把同一个百分比和步骤同时写入
     * 数据库与 Redis Stream，保证刷新页面和实时订阅看到的内容一致。</p>
     *
     * @param message AI Service 发布的 JSON 进度消息
     */
    @RabbitListener(queues = "${messaging.analysis.progress-queue}")
    @Transactional
    public void onProgress(String message) {
        try {
            // 进度消息和结果消息使用同样的来源校验，避免未知服务伪造任务进度。
            JsonNode node = objectMapper.readTree(message);
            if (!EXPECTED_SOURCE.equals(node.path("source").asText())) {
                log.warn("忽略未知来源的分析进度消息");
                return;
            }
            // 没有 taskId 时无法判断这条进度属于哪份文档，直接丢弃。
            String taskId = node.path("taskId").asText("");
            if (taskId.isBlank()) {
                log.warn("分析进度消息缺少 taskId");
                return;
            }
            // 锁住任务后再判断状态，避免取消和进度回写交错执行。
            taskRepository.findByIdForUpdate(taskId).ifPresent(task -> {
                // 只有处理中任务接受进度，CANCELLING 和终态任务的旧进度必须丢弃。
                if (task.getStatus() != TaskStatus.PROCESSING) return;
                // 外部服务的进度可能越界，先压缩到 0~100 再交给前端展示。
                int progress = Math.max(0, Math.min(100, node.path("progress").asInt()));
                String currentStep = node.path("currentStep").asText("分析中...");
                // 数据库用于刷新后的恢复，Stream 用于当前已连接页面，两处写入同一份值。
                task.setProgress(progress);
                task.setCurrentStep(currentStep);
                taskRepository.save(task);
                streamService.publishProgress(taskId, progress, currentStep);
            });
        } catch (Exception e) {
            // 进度不是最终结果，解析失败时只记录日志，不影响后续消息继续消费。
            log.error("处理进度更新失败", e);
        }
    }
}
