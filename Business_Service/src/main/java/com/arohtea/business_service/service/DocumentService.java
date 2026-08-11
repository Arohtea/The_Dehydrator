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
 * <p>一份分析文档有三份互相对应的资源：MinIO 中的原文件、Business Service
 * 数据库中的元数据、AI Service/Qdrant 中用于检索的向量。上传先保存原文件和
 * 元数据，再把向量化放到后台；删除则必须先阻止新任务、等待正在运行的任务确认
 * 终止，最后才清理这三份资源。</p>
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
     * <p>同步部分只负责把文件可靠地落到 MinIO 和数据库，使上传接口尽快返回；
     * 异步部分使用同一份设置快照调用向量模型，并在向量成功后把文档镜像归档到
     * 系统资料库。任何一步失败都会尝试删除已经创建的向量、镜像和原始资源，避免
     * 只剩“半份文档”。</p>
     *
     * @param file 上传文件
     * @return 已保存的业务文档
     * @throws Exception 文件存储或读取失败
     */
    public Document upload(MultipartFile file) throws Exception {
        // 先冻结本次上传的配置；后台线程不能在运行中途重新读取设置，否则同一文件
        // 可能被不同的模型或不同的分块参数处理。
        var settings = settingsService.get();
        var vectorModel = settingsService.requireVectorModelConfig(settings);
        settingsService.requireTextModelConfig(settings);
        ensureBucket();

        // 使用随机目录保存原文件，避免同名文件互相覆盖；数据库记录保存这个对象路径。
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
        // 先写数据库再启动异步任务，后台回调才能通过业务 ID 找回这份文档。
        Document saved = documentRepository.save(doc);
        referenceArchiveService.createAnalysisMirror(saved);

        // 保存设置快照并在异步线程中复用，保证一次上传不会因设置页修改而使用混合参数。
        String docId = saved.getId();
        CompletableFuture.runAsync(() -> {
            String aiDocId = null;
            try {
                // AI Service 返回的 ID 是向量资源的唯一凭据；只有拿到它后文档才允许启动分析。
                aiDocId = aiServiceClient.uploadDocument(
                        fileBytes, file.getOriginalFilename(),
                        vectorModel, settings.getChunkSize(), settings.getChunkOverlap());
                // 回写时重新加文档行锁，并检查 deleting 标志，拦截删除流程启动前已经在路上的旧回调。
                Document current = vectorizationService.complete(docId, aiDocId);
                if (current == null) {
                    // 文档已经被删除或进入删除流程，不能把向量 ID 写回数据库，直接回收远程向量。
                    aiServiceClient.deleteDocument(aiDocId);
                    log.info("文档已删除或进入删除流程，回收分析向量: {} -> {}", docId, aiDocId);
                    return;
                }
                // 向量准备完成后再归档，归档服务才能把同一份内容建立为可检索的参考资料镜像。
                referenceArchiveService.finalizeAnalysisMirror(current, settings);
                log.info("文档向量化完成: {} -> {}", docId, aiDocId);
            } catch (Exception e) {
                // 后台线程无法把异常直接返回给上传请求，因此记录失败并执行补偿清理。
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
     * <p>删除不是单个数据库 DELETE：第一步标记文档正在删除并给活动任务发停止信号，
     * 第二步等待 AI Service 确认，第三步才删除原始文档、自动归档镜像、MinIO 对象、
     * AI 向量和历史任务。等待超时会保留资源并返回冲突，让调用方稍后重试。</p>
     *
     * @param id 文档 ID
     * @throws Exception 外部资源删除失败
     * @throws DocumentDeletionInProgressException 取消确认超时
     */
    public void delete(String id) throws Exception {
        // 先把文档关进“删除中”状态，阻止新的分析启动和旧异步回调继续写入。
        if (!analysisService.beginDocumentDeletion(id)) {
            return;
        }
        if (!analysisService.awaitCancellation(id)) {
            throw new DocumentDeletionInProgressException(
                    "分析服务未在规定时间内确认终止，文档和资源已保留，请稍后重试删除");
        }
        // 只有所有活动任务都收口后，删除向量和对象才不会与远程处理并发。
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
