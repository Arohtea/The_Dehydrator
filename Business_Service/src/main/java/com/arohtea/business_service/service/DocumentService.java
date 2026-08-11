package com.arohtea.business_service.service;

import com.arohtea.business_service.client.AiServiceClient;
import com.arohtea.business_service.dto.DocumentSummaryResponse;
import com.arohtea.business_service.model.Document;
import com.arohtea.business_service.model.DocumentDeletionInProgressException;
import com.arohtea.business_service.repository.AnalysisTaskRepository;
import com.arohtea.business_service.repository.DocumentRepository;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 分析文档的上传、查询和删除编排服务。
 *
 * <p>上传先持久化 MinIO 对象和 PostgreSQL 元数据，再异步调用 AI Service；删除
 * 则反向等待活动任务取消后才清理数据库、对象存储和向量。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    private final DocumentRepository documentRepository;
    private final AnalysisTaskRepository analysisTaskRepository;
    private final MinioClient minioClient;
    private final AiServiceClient aiServiceClient;
    private final SystemSettingsService settingsService;
    private final ReferenceArchiveService referenceArchiveService;
    private final AnalysisService analysisService;
    private final DocumentVectorizationService vectorizationService;

    @Value("${minio.bucket}")
    private String bucket;

    /**
     * 校验数据库模型配置后保存文档，并异步完成向量化与自动归档。
     *
     * @param file 上传文件
     * @return 已保存的业务文档
     * @throws Exception 文件存储或读取失败
     */
    public Document upload(MultipartFile file) throws Exception {
        var settings = settingsService.get();
        var vectorModel = settingsService.requireVectorModelConfig(settings);
        settingsService.requireTextModelConfig(settings);
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
        referenceArchiveService.createAnalysisMirror(saved);

        // 保存设置快照并在异步线程中复用，保证一次上传不会因设置页修改而使用混合参数。
        String docId = saved.getId();
        CompletableFuture.runAsync(() -> {
            String aiDocId = null;
            try {
                aiDocId = aiServiceClient.uploadDocument(
                        fileBytes, file.getOriginalFilename(),
                        vectorModel, settings.getChunkSize(), settings.getChunkOverlap());
                Document current = vectorizationService.complete(docId, aiDocId);
                if (current == null) {
                    aiServiceClient.deleteDocument(aiDocId);
                    log.info("文档已删除或进入删除流程，回收分析向量: {} -> {}", docId, aiDocId);
                    return;
                }
                referenceArchiveService.finalizeAnalysisMirror(current, settings);
                log.info("文档向量化完成: {} -> {}", docId, aiDocId);
            } catch (Exception e) {
                log.error("文档向量化失败: {}", docId, e);
                if (aiDocId != null && !aiDocId.isBlank()) {
                    try {
                        aiServiceClient.deleteDocument(aiDocId);
                    } catch (Exception cleanupException) {
                        log.warn("清理失败的分析向量失败: {}", docId, cleanupException);
                    }
                }
                try {
                    referenceArchiveService.deleteSourceDocumentWithMirrors(docId);
                } catch (Exception cleanupException) {
                    log.warn("清理失败的分析文档资源失败: {}", docId, cleanupException);
                }
            }
        });

        return saved;
    }

    /**
     * 返回文档列表及最新任务状态摘要。
     *
     * @return 按创建时间返回的文档摘要
     */
    public List<DocumentSummaryResponse> list() {
        return documentRepository.findAll().stream()
                .map(document -> DocumentSummaryResponse.from(
                        document,
                        analysisTaskRepository.findFirstByDocumentIdOrderByCreatedAtDesc(document.getId()).orElse(null)))
                .collect(Collectors.toList());
    }

    /**
     * 查询单个文档。
     *
     * @param id 文档 ID
     * @return 文档，不存在时返回 null
     */
    public Document getById(String id) {
        return documentRepository.findById(id).orElse(null);
    }

    /**
     * 先等待活动分析任务确认终止，再删除文档及其外部资源。
     *
     * @param id 文档 ID
     * @throws Exception 外部资源删除失败
     * @throws DocumentDeletionInProgressException 取消确认超时
     */
    public void delete(String id) throws Exception {
        if (!analysisService.beginDocumentDeletion(id)) {
            return;
        }
        if (!analysisService.awaitCancellation(id)) {
            throw new DocumentDeletionInProgressException(
                    "分析服务未在规定时间内确认终止，文档和资源已保留，请稍后重试删除");
        }
        referenceArchiveService.deleteSourceDocumentWithMirrors(id);
        analysisService.removeTasksForDeletedDocument(id);
    }

    /**
     * 确保分析文档使用的 MinIO bucket 已创建。
     *
     * @throws Exception MinIO 查询或创建 bucket 失败
     */
    private void ensureBucket() throws Exception {
        if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }
}
