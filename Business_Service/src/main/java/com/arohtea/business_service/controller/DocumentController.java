package com.arohtea.business_service.controller;

import com.arohtea.business_service.model.Document;
import com.arohtea.business_service.dto.DocumentSummaryResponse;
import com.arohtea.business_service.service.DocumentService;
import com.arohtea.business_service.service.RequestRateLimiter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;
    private final RequestRateLimiter requestRateLimiter;

    @PostMapping("/upload")
    public ResponseEntity<Document> upload(
            @RequestParam("file") MultipartFile file) throws Exception {
        if (!requestRateLimiter.allowUpload()) {
            return ResponseEntity.status(429).build();
        }
        return ResponseEntity.ok(documentService.upload(file));
    }

    @GetMapping
    public ResponseEntity<List<DocumentSummaryResponse>> list() {
        return ResponseEntity.ok(documentService.list());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Document> getById(@PathVariable("id") String id) {
        Document doc = documentService.getById(id);
        if (doc == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(doc);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") String id) throws Exception {
        documentService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
