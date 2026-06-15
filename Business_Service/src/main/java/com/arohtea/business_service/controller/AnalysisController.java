package com.arohtea.business_service.controller;

import com.arohtea.business_service.model.AnalysisTask;
import com.arohtea.business_service.model.Document;
import com.arohtea.business_service.service.AnalysisService;
import com.arohtea.business_service.service.DocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api/analysis")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AnalysisController {

    private final AnalysisService analysisService;
    private final DocumentService documentService;

    @PostMapping("/start")
    public ResponseEntity<?> start(@RequestBody Map<String, Object> body) {
        String docId = body.get("documentId") instanceof String value ? value : null;
        String mode = body.get("mode") instanceof String value ? value : "deep";
        List<String> referenceLibraryIds = extractStringList(body.get("referenceLibraryIds"));
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
        AnalysisTask task = analysisService.createTask(
                docId, doc.getAiDocId(), mode, referenceLibraryIds);
        return ResponseEntity.ok(task);
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
