package com.arohtea.business_service.controller;

import com.arohtea.business_service.model.AnalysisTask;
import com.arohtea.business_service.model.Document;
import com.arohtea.business_service.dto.AnalysisTaskResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.arohtea.business_service.service.AnalysisService;
import com.arohtea.business_service.service.DocumentService;
import com.arohtea.business_service.service.RequestRateLimiter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 分析任务创建、查询和取消接口。
 *
 * <p>控制器只做请求格式和 HTTP 状态码适配，任务并发、配置快照和状态迁移由
 * {@link AnalysisService} 负责。这样 HTTP 层不直接操作任务状态，也不会绕过行锁
 * 启动或取消远程分析。</p>
 */
@RestController
@RequestMapping("/api/analysis")
@RequiredArgsConstructor
public class AnalysisController {

    private final AnalysisService analysisService;
    private final DocumentService documentService;
    private final RequestRateLimiter requestRateLimiter;
    private final ObjectMapper objectMapper;

    /**
     * 为已完成向量化的文档创建分析任务。
     *
     * @param body 包含 documentId、mode 和参考资料库 ID 的请求体
     * @return 创建后的任务，或参数错误、资源未就绪、限流和业务冲突响应
     */
    @PostMapping("/start")
    public ResponseEntity<?> start(@RequestBody Map<String, Object> body) {
        // Map 请求体允许前端传入任意 JSON，先把关键字段收敛成服务层认识的类型。
        String docId = body.get("documentId") instanceof String value ? value : null;
        String mode = body.get("mode") instanceof String value ? value : "deep";
        List<String> referenceLibraryIds = extractStringList(body.get("referenceLibraryIds"));
        // 参考库数量上限保护后续检索和消息体大小，超过时无需进入数据库事务。
        if (referenceLibraryIds.size() > 50) {
            return ResponseEntity.unprocessableEntity()
                    .body(Map.of("error", "参考资料集数量不能超过 50"));
        }
        if (docId == null || docId.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "documentId不能为空"));
        }
        // 先给出明确的文档状态响应，避免让服务层用同一个异常表示“不存在”和“向量未完成”。
        Document doc = documentService.getById(docId);
        if (doc == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "文档不存在"));
        }
        if (doc.getAiDocId() == null || doc.getAiDocId().isBlank()) {
            return ResponseEntity.status(202)
                    .body(Map.of("error", "文档正在向量化，请稍后再试"));
        }
        // 只有文档存在且已准备好向量后才消耗分析额度，查询失败不会浪费令牌。
        if (!requestRateLimiter.allowAnalysisStart()) {
            return ResponseEntity.status(429)
                    .body(Map.of("error", "分析请求过于频繁"));
        }
        try {
            // 具体的并发锁、模型快照和 RabbitMQ 事件由服务层完成。
            AnalysisTask task = analysisService.createTask(
                    docId, mode, referenceLibraryIds);
            return ResponseEntity.ok(AnalysisTaskResponse.from(task, objectMapper));
        } catch (IllegalArgumentException exception) {
            // 参数或配置问题用 422，前端应修改请求或设置后再提交。
            return ResponseEntity.unprocessableEntity()
                    .body(Map.of("error", exception.getMessage()));
        } catch (IllegalStateException exception) {
            // 资源未就绪或并发额度已满使用 429，表示稍后重试可能成功。
            return ResponseEntity.status(429)
                    .body(Map.of("error", exception.getMessage()));
        }
    }

    /**
     * 查询单个分析任务及其结构化结果。
     *
     * @param taskId 任务 ID
     * @return 任务响应；任务不存在时返回 404
     */
    @GetMapping("/task/{taskId}")
    public ResponseEntity<?> getTask(
            @PathVariable("taskId") String taskId) {
        AnalysisTask task = analysisService.getTask(taskId);
        if (task == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(AnalysisTaskResponse.from(task, objectMapper));
    }

    /**
     * 查询指定文档的全部历史任务。
     *
     * @param documentId 文档 ID
     * @return 按创建时间升序排列的任务响应列表
     */
    @GetMapping("/document/{documentId}")
    public ResponseEntity<List<AnalysisTaskResponse>> getByDocument(
            @PathVariable("documentId") String documentId) {
        return ResponseEntity.ok(analysisService.getByDocumentId(documentId).stream()
                .map(task -> AnalysisTaskResponse.from(task, objectMapper))
                .toList());
    }

    /**
     * 请求取消分析任务。
     *
     * @param taskId 任务 ID
     * @return 进入 CANCELLING 或已结束状态的任务响应；不存在时返回 404
     */
    @PostMapping("/task/{taskId}/cancel")
    public ResponseEntity<?> cancel(@PathVariable("taskId") String taskId) {
        AnalysisTask task = analysisService.cancelTask(taskId);
        if (task == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(AnalysisTaskResponse.from(task, objectMapper));
    }

    /**
     * 从非类型化 JSON 请求中提取字符串列表，丢弃空值和非字符串元素。
     *
     * @param value 请求体中的任意值
     * @return 可交给服务层继续清洗的字符串列表
     */
    private List<String> extractStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Objects::nonNull)
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toList();
    }
}
