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

    private void deleteSourceDocumentRecordAndResources(Document document) {
        if (document.getAiDocId() != null && !document.getAiDocId().isBlank()) {
            deleteAiDocument(document.getAiDocId(), "删除分析文档向量失败");
        }
        deleteObject(document.getMinioPath(), "删除分析文档MinIO文件失败");
        documentRepository.deleteById(document.getId());
        documentRepository.flush();
    }

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

    private void deleteAiDocument(String aiDocId, String message) {
        try {
            aiServiceClient.deleteDocument(aiDocId);
        } catch (Exception e) {
            throw new IllegalStateException(message, e);
        }
    }

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

    private void safeDeleteAiDocument(String aiDocId, String message) {
        try {
            aiServiceClient.deleteDocument(aiDocId);
        } catch (Exception e) {
            log.warn("{}: {}", message, e.getMessage());
        }
    }

    private String normalizeName(String name, String errorMessage) {
        String normalized = name == null ? "" : name.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(errorMessage);
        }
        return normalized;
    }
}
