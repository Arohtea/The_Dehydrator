package com.arohtea.business_service.service;

import com.arohtea.business_service.client.AiServiceClient;
import com.arohtea.business_service.model.Document;
import com.arohtea.business_service.model.ReferenceCategory;
import com.arohtea.business_service.model.ReferenceDocument;
import com.arohtea.business_service.model.ReferenceFolder;
import com.arohtea.business_service.model.ReferenceLibrary;
import com.arohtea.business_service.model.SystemSettings;
import com.arohtea.business_service.repository.DocumentRepository;
import com.arohtea.business_service.repository.ReferenceCategoryRepository;
import com.arohtea.business_service.repository.ReferenceDocumentRepository;
import com.arohtea.business_service.repository.ReferenceFolderRepository;
import com.arohtea.business_service.repository.ReferenceLibraryRepository;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 分析文档与参考资料库之间的镜像、归档分类和级联删除服务。
 *
 * <p>分析文档与自动归档镜像共享 MinIO 原始对象，但在 Qdrant 中拥有不同的
 * 逻辑文档 ID；删除时必须同时处理数据库记录、对象存储和向量资源。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReferenceArchiveService {

    public static final String DEFAULT_FOLDER_NAME = "待整理";
    public static final String DEFAULT_CATEGORY_NAME = "未分类";
    private static final double AUTO_CLASSIFY_CONFIDENCE_THRESHOLD = 0.6d;

    private final ReferenceLibraryRepository referenceLibraryRepository;
    private final ReferenceDocumentRepository referenceDocumentRepository;
    private final ReferenceFolderRepository referenceFolderRepository;
    private final ReferenceCategoryRepository referenceCategoryRepository;
    private final DocumentRepository documentRepository;
    private final MinioClient minioClient;
    private final AiServiceClient aiServiceClient;

    @Value("${minio.bucket}")
    private String bucket;

    /**
     * 为新上传的分析文档创建默认资料库镜像。
     *
     * @param sourceDocument 已保存的分析文档
     * @return 已存在或新创建的镜像记录
     */
    @Transactional
    public ReferenceDocument createAnalysisMirror(Document sourceDocument) {
        return referenceDocumentRepository.findFirstBySourceDocumentId(sourceDocument.getId())
                .orElseGet(() -> {
                    ReferenceLibrary library = ensureAutoArchiveLibrary();
                    ReferenceFolder folder = ensureFolder(library.getId(), DEFAULT_FOLDER_NAME);
                    ReferenceCategory category = ensureCategory(library.getId(), DEFAULT_CATEGORY_NAME);

                    ReferenceDocument document = new ReferenceDocument();
                    document.setLibraryId(library.getId());
                    document.setFilename(sourceDocument.getFilename());
                    document.setDisplayName(sourceDocument.getFilename());
                    document.setFolderId(folder.getId());
                    document.setCategoryId(category.getId());
                    document.setSourceDocumentId(sourceDocument.getId());
                    document.setMinioPath(sourceDocument.getMinioPath());
                    document.setFileSize(sourceDocument.getFileSize());
                    return referenceDocumentRepository.save(document);
                });
    }

    /**
     * 使用上传时的设置快照完成分析文档镜像归档，避免异步期间配置漂移。
     *
     * @param sourceDocument 已完成向量化的原始文档
     * @param settings 上传时读取的数据库设置快照
     *
     * <p>低置信度的模型建议只保留默认位置，不自动创建目录，避免模型猜测改变
     * 用户资料库结构。</p>
     */
    public void finalizeAnalysisMirror(Document sourceDocument, SystemSettings settings) {
        if (sourceDocument.getAiDocId() == null || sourceDocument.getAiDocId().isBlank()) {
            return;
        }
        ReferenceDocument mirror = referenceDocumentRepository.findFirstBySourceDocumentId(sourceDocument.getId()).orElse(null);
        if (mirror == null) {
            return;
        }

        List<String> folderCandidates = referenceFolderRepository.findByLibraryIdOrderByCreatedAtAsc(mirror.getLibraryId())
                .stream()
                .map(ReferenceFolder::getName)
                .toList();
        List<String> categoryCandidates = referenceCategoryRepository.findByLibraryIdOrderByCreatedAtAsc(mirror.getLibraryId())
                .stream()
                .map(ReferenceCategory::getName)
                .toList();

        AiServiceClient.ArchiveReferenceResult result = aiServiceClient.archiveReferenceDocument(
                sourceDocument.getAiDocId(),
                mirror.getLibraryId(),
                sourceDocument.getFilename(),
                folderCandidates,
                categoryCandidates,
                settings.getTextModelConfig()
        );

        ReferenceDocument currentMirror = referenceDocumentRepository.findById(mirror.getId()).orElse(null);
        if (currentMirror == null) {
            if (result.docId() != null && !result.docId().isBlank()) {
                safeDeleteAiDocument(result.docId(), "分析论文镜像已删除，回收参考向量失败");
            }
            return;
        }

        currentMirror.setAiDocId(result.docId());
        if (result.confidence() != null && result.confidence() >= AUTO_CLASSIFY_CONFIDENCE_THRESHOLD) {
            if (result.folderName() != null && !result.folderName().isBlank()) {
                currentMirror.setFolderId(ensureFolder(currentMirror.getLibraryId(), result.folderName().trim()).getId());
            }
            if (result.categoryName() != null && !result.categoryName().isBlank()) {
                currentMirror.setCategoryId(ensureCategory(currentMirror.getLibraryId(), result.categoryName().trim()).getId());
            }
        }
        referenceDocumentRepository.save(currentMirror);
    }

    /**
     * 获取或创建系统自动归档资料库，并确保默认位置存在。
     *
     * @return 系统自动归档资料库
     */
    @Transactional
    public ReferenceLibrary ensureAutoArchiveLibrary() {
        ReferenceLibrary library = referenceLibraryRepository.findBySystemKey(ReferenceLibrary.AUTO_ANALYSIS_ARCHIVE_KEY)
                .orElseGet(() -> {
                    ReferenceLibrary created = new ReferenceLibrary();
                    created.setSystemKey(ReferenceLibrary.AUTO_ANALYSIS_ARCHIVE_KEY);
                    created.setName(ReferenceLibrary.AUTO_ANALYSIS_ARCHIVE_NAME);
                    return referenceLibraryRepository.save(created);
                });
        ensureFolder(library.getId(), DEFAULT_FOLDER_NAME);
        ensureCategory(library.getId(), DEFAULT_CATEGORY_NAME);
        return library;
    }

    /**
     * 获取或创建资料库内指定名称的文件夹。
     *
     * @param libraryId 资料库 ID
     * @param name 文件夹名称
     * @return 已存在或新创建的文件夹
     * @throws IllegalArgumentException 名称为空
     */
    @Transactional
    public ReferenceFolder ensureFolder(String libraryId, String name) {
        String normalizedName = normalizeName(name, "文件夹名称不能为空");
        return referenceFolderRepository.findByLibraryIdAndName(libraryId, normalizedName)
                .orElseGet(() -> {
                    ReferenceFolder folder = new ReferenceFolder();
                    folder.setLibraryId(libraryId);
                    folder.setName(normalizedName);
                    return referenceFolderRepository.save(folder);
                });
    }

    /**
     * 获取或创建资料库内指定名称的分类。
     *
     * @param libraryId 资料库 ID
     * @param name 分类名称
     * @return 已存在或新创建的分类
     * @throws IllegalArgumentException 名称为空
     */
    @Transactional
    public ReferenceCategory ensureCategory(String libraryId, String name) {
        String normalizedName = normalizeName(name, "分类名称不能为空");
        return referenceCategoryRepository.findByLibraryIdAndName(libraryId, normalizedName)
                .orElseGet(() -> {
                    ReferenceCategory category = new ReferenceCategory();
                    category.setLibraryId(libraryId);
                    category.setName(normalizedName);
                    return referenceCategoryRepository.save(category);
                });
    }

    /**
     * 删除原始分析文档及其所有自动归档镜像和外部资源。
     *
     * @param documentId 原始业务文档 ID
     */
    @Transactional
    public void deleteSourceDocumentWithMirrors(String documentId) {
        Document document = documentRepository.findById(documentId).orElse(null);
        if (document == null) {
            return;
        }

        List<ReferenceDocument> mirrors = referenceDocumentRepository.findBySourceDocumentId(documentId);
        for (ReferenceDocument mirror : mirrors) {
            deleteReferenceDocumentRecordAndResources(mirror);
        }
        deleteSourceDocumentRecordAndResources(document);
        log.info("审计: 删除原始文档及自动归档镜像 documentId={} mirrorCount={}", documentId, mirrors.size());
    }

    /**
     * 删除参考资料；如果资料是分析文档镜像，则回溯删除原始文档及全部镜像。
     *
     * @param referenceDocumentId 参考资料记录 ID
     */
    public void deleteReferenceDocumentWithLinkedSource(String referenceDocumentId) {
        ReferenceDocument document = referenceDocumentRepository.findById(referenceDocumentId).orElse(null);
        if (document == null) {
            return;
        }
        if (document.getSourceDocumentId() != null && !document.getSourceDocumentId().isBlank()) {
            deleteSourceDocumentWithMirrors(document.getSourceDocumentId());
            return;
        }
        deleteReferenceDocumentRecordAndResources(document);
        log.info("审计: 删除独立参考资料 documentId={}", referenceDocumentId);
    }

    /**
     * 删除独立参考资料的向量、MinIO 对象和数据库记录。
     *
     * @param document 待删除的参考资料记录
     */
    private void deleteReferenceDocumentRecordAndResources(ReferenceDocument document) {
        if (document.getAiDocId() != null && !document.getAiDocId().isBlank()) {
            deleteAiDocument(document.getAiDocId(), "删除参考资料向量失败");
        }
        if (document.getSourceDocumentId() == null || document.getSourceDocumentId().isBlank()) {
            deleteObject(document.getMinioPath(), "删除参考资料MinIO文件失败");
        }
        referenceDocumentRepository.deleteById(document.getId());
        referenceDocumentRepository.flush();
    }

    /**
     * 删除原始分析文档的向量、MinIO 对象和数据库记录。
     *
     * @param document 待删除的分析文档记录
     */
    private void deleteSourceDocumentRecordAndResources(Document document) {
        if (document.getAiDocId() != null && !document.getAiDocId().isBlank()) {
            deleteAiDocument(document.getAiDocId(), "删除分析文档向量失败");
        }
        deleteObject(document.getMinioPath(), "删除分析文档MinIO文件失败");
        documentRepository.deleteById(document.getId());
        documentRepository.flush();
    }

    /**
     * 强一致删除 MinIO 对象，失败时阻止数据库删除继续进行。
     *
     * @param minioPath MinIO 对象键
     * @param message 删除失败时的业务错误
     * @throws IllegalStateException MinIO 删除失败
     */
    private void deleteObject(String minioPath, String message) {
        if (minioPath == null || minioPath.isBlank()) {
            return;
        }
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(minioPath)
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException(message, e);
        }
    }

    /**
     * 强一致删除 AI Service 中的向量文档。
     *
     * @param aiDocId AI Service 文档 ID
     * @param message 删除失败时的业务错误
     * @throws IllegalStateException AI Service 调用失败
     */
    private void deleteAiDocument(String aiDocId, String message) {
        try {
            aiServiceClient.deleteDocument(aiDocId);
        } catch (Exception e) {
            throw new IllegalStateException(message, e);
        }
    }

    /**
     * 在补偿清理路径中尽力删除 MinIO 对象，不再向外抛出异常。
     *
     * @param minioPath MinIO 对象键
     * @param message 日志提示
     */
    private void safeDeleteObject(String minioPath, String message) {
        if (minioPath == null || minioPath.isBlank()) {
            return;
        }
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(minioPath)
                    .build());
        } catch (Exception e) {
            log.warn("{}: {}", message, e.getMessage());
        }
    }

    /**
     * 在补偿清理路径中尽力删除 AI 向量，不再覆盖原始异常。
     *
     * @param aiDocId AI Service 文档 ID
     * @param message 日志提示
     */
    private void safeDeleteAiDocument(String aiDocId, String message) {
        try {
            aiServiceClient.deleteDocument(aiDocId);
        } catch (Exception e) {
            log.warn("{}: {}", message, e.getMessage());
        }
    }

    /**
     * 规范化目录名称并拒绝空名称。
     *
     * @param name 原始名称
     * @param errorMessage 空名称时返回的业务错误
     * @return 去除首尾空白后的名称
     * @throws IllegalArgumentException 名称为空
     */
    private String normalizeName(String name, String errorMessage) {
        String normalized = name == null ? "" : name.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(errorMessage);
        }
        return normalized;
    }
}
