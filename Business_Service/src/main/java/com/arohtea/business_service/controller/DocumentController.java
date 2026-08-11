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

/**
 * 分析文档上传、查询和删除接口。
 */
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;
    private final RequestRateLimiter requestRateLimiter;

    /**
     * 接收分析文档并启动后台向量化。
     *
     * @param file 用户上传的 PDF、DOCX 或 TXT 文件
     * @return 已保存的文档元数据；请求过于频繁时返回 429
     * @throws Exception 对象存储或元数据保存失败
     */
    @PostMapping("/upload")
    public ResponseEntity<Document> upload(
            @RequestParam("file") MultipartFile file) throws Exception {
        if (!requestRateLimiter.allowUpload()) {
            return ResponseEntity.status(429).build();
        }
        return ResponseEntity.ok(documentService.upload(file));
    }

    /**
     * 返回文档列表及最新分析/向量化状态摘要。
     *
     * @return 文档摘要列表
     */
    @GetMapping
    public ResponseEntity<List<DocumentSummaryResponse>> list() {
        return ResponseEntity.ok(documentService.list());
    }

    /**
     * 查询单个文档的完整元数据。
     *
     * @param id 文档 ID
     * @return 文档实体；不存在时返回 404
     */
    @GetMapping("/{id}")
    public ResponseEntity<Document> getById(@PathVariable("id") String id) {
        Document doc = documentService.getById(id);
        if (doc == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(doc);
    }

    /**
     * 等待活动任务取消后删除文档及其镜像、对象和向量。
     *
     * @param id 文档 ID
     * @return 删除成功时返回 204
     * @throws Exception 外部资源删除失败
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") String id) throws Exception {
        documentService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
