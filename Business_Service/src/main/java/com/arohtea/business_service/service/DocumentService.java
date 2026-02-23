package com.arohtea.business_service.service;

import com.arohtea.business_service.client.AiServiceClient;
import com.arohtea.business_service.model.Document;
import com.arohtea.business_service.repository.DocumentRepository;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.RemoveObjectArgs;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    private final DocumentRepository documentRepository;
    private final MinioClient minioClient;
    private final AiServiceClient aiServiceClient;
    private final SystemSettingsService settingsService;

    @Value("${minio.bucket}")
    private String bucket;

    public Document upload(MultipartFile file) throws Exception {
        ensureBucket();

        String path = UUID.randomUUID() + "/" + file.getOriginalFilename();
        byte[] fileBytes = file.getBytes();

        minioClient.putObject(PutObjectArgs.builder()
                .bucket(bucket)
                .object(path)
                .stream(new ByteArrayInputStream(fileBytes), fileBytes.length, -1)
                .contentType(file.getContentType())
                .build());

        Document doc = new Document();
        doc.setFilename(file.getOriginalFilename());
        doc.setMinioPath(path);
        doc.setFileSize(file.getSize());
        Document saved = documentRepository.save(doc);

        // 异步调用AI_Service解析、分块、向量化
        String docId = saved.getId();
        var settings = settingsService.get();
        CompletableFuture.runAsync(() -> {
            try {
                String aiDocId = aiServiceClient.uploadDocument(
                        fileBytes, file.getOriginalFilename(),
                        settings.getApiKey(), settings.getChunkSize(), settings.getChunkOverlap());
                saved.setAiDocId(aiDocId);
                documentRepository.save(saved);
                log.info("文档向量化完成: {} -> {}", docId, aiDocId);
            } catch (Exception e) {
                log.error("文档向量化失败: {}", docId, e);
            }
        });

        return saved;
    }

    public List<Document> list() {
        return documentRepository.findAll();
    }

    public Document getById(String id) {
        return documentRepository.findById(id).orElse(null);
    }

    @Transactional
    public void delete(String id) throws Exception {
        Document doc = documentRepository.findById(id).orElse(null);
        if (doc == null) return;
        String minioPath = doc.getMinioPath();
        String aiDocId = doc.getAiDocId();
        // 先删数据库，避免和异步线程冲突
        documentRepository.deleteById(id);
        documentRepository.flush();
        // 再删外部资源
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket).object(minioPath).build());
        } catch (Exception e) { log.warn("删除MinIO文件失败: {}", e.getMessage()); }
        if (aiDocId != null) {
            try { aiServiceClient.deleteDocument(aiDocId); }
            catch (Exception e) { log.warn("删除向量失败: {}", e.getMessage()); }
        }
    }

    private void ensureBucket() throws Exception {
        if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }
}
