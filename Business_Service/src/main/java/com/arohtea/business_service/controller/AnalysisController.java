package com.arohtea.business_service.controller;

import com.arohtea.business_service.model.AnalysisTask;
import com.arohtea.business_service.model.Document;
import com.arohtea.business_service.service.AnalysisService;
import com.arohtea.business_service.service.DocumentService;
import com.arohtea.business_service.service.RequestRateLimiter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api/analysis")
@RequiredArgsConstructor
public class AnalysisController {

    private final AnalysisService analysisService;
    private final DocumentService documentService;
    private final RequestRateLimiter requestRateLimiter;

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
        if (doc.getAiDocId() == null) {
            return ResponseEntity.status(202)
                    .body(Map.of("error", "文档正在向量化，请稍后再试"));
        }
        if (!requestRateLimiter.allowAnalysisStart()) {
            return ResponseEntity.status(429)
                    .body(Map.of("error", "分析请求过于频繁"));
        }
        try {
            AnalysisTask task = analysisService.createTask(
                    docId, doc.getAiDocId(), mode, referenceLibraryIds);
            return ResponseEntity.ok(task);
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.unprocessableEntity()
                    .body(Map.of("error", exception.getMessage()));
        } catch (IllegalStateException exception) {
            return ResponseEntity.status(429)
                    .body(Map.of("error", exception.getMessage()));
        }
    }

    @GetMapping("/task/{taskId}")
    public ResponseEntity<?> getTask(
            @PathVariable("taskId") String taskId) {
        AnalysisTask task = analysisService.getTask(taskId);
        if (task == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(task);
    }

    @GetMapping("/document/{documentId}")
    public ResponseEntity<List<AnalysisTask>> getByDocument(
            @PathVariable("documentId") String documentId) {
        return ResponseEntity.ok(
                analysisService.getByDocumentId(documentId));
    }

    @PostMapping("/task/{taskId}/cancel")
    public ResponseEntity<?> cancel(@PathVariable("taskId") String taskId) {
        AnalysisTask task = analysisService.cancelTask(taskId);
        if (task == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(task);
    }

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
