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
 * `AnalysisService` 负责。</p>
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
        String docId = body.get("documentId") instanceof String value ? value : null;
        String mode = body.get("mode") instanceof String value ? value : "deep";
        List<String> referenceLibraryIds = extractStringList(body.get("referenceLibraryIds"));
        if (referenceLibraryIds.size() > 50) {
            return ResponseEntity.unprocessableEntity()
                    .body(Map.of("error", "参考资料集数量不能超过 50"));
        }
        if (docId == null || docId.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "documentId不能为空"));
        }
        Document doc = documentService.getById(docId);
        if (doc == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "文档不存在"));
        }
        if (doc.getAiDocId() == null || doc.getAiDocId().isBlank()) {
            return ResponseEntity.status(202)
                    .body(Map.of("error", "文档正在向量化，请稍后再试"));
        }
        if (!requestRateLimiter.allowAnalysisStart()) {
            return ResponseEntity.status(429)
                    .body(Map.of("error", "分析请求过于频繁"));
        }
        try {
            AnalysisTask task = analysisService.createTask(
                    docId, mode, referenceLibraryIds);
            return ResponseEntity.ok(AnalysisTaskResponse.from(task, objectMapper));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.unprocessableEntity()
                    .body(Map.of("error", exception.getMessage()));
        } catch (IllegalStateException exception) {
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
