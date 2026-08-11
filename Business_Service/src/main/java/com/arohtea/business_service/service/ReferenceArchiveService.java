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
 * <p>自动归档不是复制一份物理文件，而是在资料库中增加一条镜像元数据：镜像和
 * 原始分析文档共用 MinIO 对象，但在 AI Service/Qdrant 中分别拥有逻辑文档 ID，
 * 这样同一文件既能参与主分析，也能作为参考资料被检索。</p>
 *
 * <p>因此删除时要根据来源关系选择路径：删除原始文档会级联删除全部镜像；删除
 * 独立参考资料只删除它自己的向量、对象和记录；镜像不能单独删除，否则会留下
 * 无法管理的原始文档关系。</p>
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
     * <p>镜像在上传阶段就建立，但此时还没有 AI 文档 ID。这样用户马上能在资料库
     * 中看到这份资料；真正的向量化完成后，{@link #finalizeAnalysisMirror} 再补齐
     * 远程 ID 和模型分类结果。</p>
     *
     * @param sourceDocument 已保存的分析文档
     * @return 已存在或新创建的镜像记录
     */
    @Transactional
    public ReferenceDocument createAnalysisMirror(Document sourceDocument) {
        // 先查已有镜像，异步回调或重复上传不会为同一原文创建多条自动归档关系。
        return referenceDocumentRepository.findFirstBySourceDocumentId(sourceDocument.getId())
                .orElseGet(() -> {
                    // 系统资料库、默认文件夹和默认分类都采用“有则复用、无则创建”，保证重复回调幂等。
                    ReferenceLibrary library = ensureAutoArchiveLibrary();
                    ReferenceFolder folder = ensureFolder(library.getId(), DEFAULT_FOLDER_NAME);
                    ReferenceCategory category = ensureCategory(library.getId(), DEFAULT_CATEGORY_NAME);

                    // 镜像只复制目录元数据和文件路径，不复制物理文件，避免同一上传占用两份对象存储。
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
     * <p>先把资料库现有目录名称交给 AI Service 作为候选项，让模型只在用户已经
     * 建立的结构中提出建议。置信度达到阈值时才创建/使用建议目录；低置信度仍放在
     * “待整理/未分类”，避免一次猜测悄悄改变资料库结构。</p>
     *
     * @param sourceDocument 已完成向量化的原始文档
     * @param settings 上传时读取的数据库设置快照
     *
     * <p>低置信度的模型建议只保留默认位置，不自动创建目录，避免模型猜测改变
     * 用户资料库结构。</p>
     */
    public void finalizeAnalysisMirror(Document sourceDocument, SystemSettings settings) {
        // 没有向量 ID 时不能调用归档接口；原文仍处于向量化阶段，保留默认镜像即可。
        if (sourceDocument.getAiDocId() == null || sourceDocument.getAiDocId().isBlank()) {
            return;
        }
        // 上传阶段会先创建镜像；如果镜像被用户删除，向量化完成后不再重新制造它。
        ReferenceDocument mirror = referenceDocumentRepository.findFirstBySourceDocumentId(sourceDocument.getId()).orElse(null);
        if (mirror == null) {
            return;
        }

        // 候选目录只来自当前资料库，避免模型把其他资料库的名称带入本库。
        List<String> folderCandidates = referenceFolderRepository.findByLibraryIdOrderByCreatedAtAsc(mirror.getLibraryId())
                .stream()
                .map(ReferenceFolder::getName)
                .toList();
        List<String> categoryCandidates = referenceCategoryRepository.findByLibraryIdOrderByCreatedAtAsc(mirror.getLibraryId())
                .stream()
                .map(ReferenceCategory::getName)
                .toList();

        // AI Service 只负责生成建议和新的参考向量，目录表的最终写入仍由本服务控制。
        AiServiceClient.ArchiveReferenceResult result = aiServiceClient.archiveReferenceDocument(
                sourceDocument.getAiDocId(),
                mirror.getLibraryId(),
                sourceDocument.getFilename(),
                folderCandidates,
                categoryCandidates,
                settings.getTextModelConfig()
        );

        // AI 调用期间用户可能已经删除原文；回写前重新查询，不能使用调用前的旧实体覆盖删除。
        ReferenceDocument currentMirror = referenceDocumentRepository.findById(mirror.getId()).orElse(null);
        if (currentMirror == null) {
            // 归档调用和用户删除并发时，回收刚由 AI Service 创建的参考向量。
            if (result.docId() != null && !result.docId().isBlank()) {
                safeDeleteAiDocument(result.docId(), "分析论文镜像已删除，回收参考向量失败");
            }
            return;
        }

        // 先保存远程参考向量 ID，之后删除镜像时才能找到并回收它。
        currentMirror.setAiDocId(result.docId());
        // 只有高置信度建议才影响目录；否则保留默认位置，等待用户手动整理。
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
     * <p>系统库通过固定 {@code systemKey} 识别，而不是依赖可变的展示名称；展示
     * 名称以后可以变化，但业务仍能找到同一个自动归档库。</p>
     *
     * @return 系统自动归档资料库
     */
    @Transactional
    public ReferenceLibrary ensureAutoArchiveLibrary() {
        // systemKey 是稳定业务标识，展示名称变化不会导致系统重新创建第二个资料库。
        ReferenceLibrary library = referenceLibraryRepository.findBySystemKey(ReferenceLibrary.AUTO_ANALYSIS_ARCHIVE_KEY)
                .orElseGet(() -> {
                    ReferenceLibrary created = new ReferenceLibrary();
                    created.setSystemKey(ReferenceLibrary.AUTO_ANALYSIS_ARCHIVE_KEY);
                    created.setName(ReferenceLibrary.AUTO_ANALYSIS_ARCHIVE_NAME);
                    return referenceLibraryRepository.save(created);
                });
        // 无论库是新建还是历史已存在，都补齐默认位置，兼容旧数据库缺少目录的情况。
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
        // 统一去除空白后再查重，避免“名称相同但首尾多一个空格”的重复目录。
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
        // 分类名称与文件夹名称采用同样的幂等规则，方便模型建议重复命中已有分类。
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
     * <p>镜像与原文可能共享同一个 MinIO 对象，所以镜像删除路径不会删除对象；原文
     * 删除路径最后才删除共享对象。两条路径都先删除 AI 向量，再删除数据库记录，
     * 任何强一致资源删除失败都会中止后续数据库删除，避免留下无法追踪的孤儿资源。</p>
     *
     * @param documentId 原始业务文档 ID
     */
    @Transactional
    public void deleteSourceDocumentWithMirrors(String documentId) {
        Document document = documentRepository.findById(documentId).orElse(null);
        if (document == null) {
            return;
        }

        // 先找出所有镜像，再逐个删除它们自己的向量，最后删除原文拥有的共享对象。
        List<ReferenceDocument> mirrors = referenceDocumentRepository.findBySourceDocumentId(documentId);
        for (ReferenceDocument mirror : mirrors) {
            // 镜像只拥有独立向量，不拥有物理文件；删除时由专用方法跳过 MinIO 对象。
            deleteReferenceDocumentRecordAndResources(mirror);
        }
        deleteSourceDocumentRecordAndResources(document);
        log.info("审计: 删除原始文档及自动归档镜像 documentId={} mirrorCount={}", documentId, mirrors.size());
    }

    /**
     * 删除参考资料；如果资料是分析文档镜像，则回溯删除原始文档及全部镜像。
     *
     * <p>这是一个防误删边界：用户从资料库页面点到镜像时，系统沿着
     * {@code sourceDocumentId} 找到真正的原始文档，走完整的级联删除流程，而不让
     * 用户只删掉资料库中的一条关系记录。</p>
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
     * <p>当 {@code sourceDocumentId} 为空时，资料自己拥有 MinIO 对象；当它非空时
     * 说明对象由原始分析文档拥有，只回收镜像自己的向量。</p>
     *
     * @param document 待删除的参考资料记录
     */
    private void deleteReferenceDocumentRecordAndResources(ReferenceDocument document) {
        // 原始文档和镜像的向量 ID 不同，必须各自清理，不能只删原文 ID。
        if (document.getAiDocId() != null && !document.getAiDocId().isBlank()) {
            deleteAiDocument(document.getAiDocId(), "删除参考资料向量失败");
        }
        // 只有独立参考资料拥有自己的 MinIO 对象，自动归档镜像必须保留共享对象给原文。
        if (document.getSourceDocumentId() == null || document.getSourceDocumentId().isBlank()) {
            deleteObject(document.getMinioPath(), "删除参考资料MinIO文件失败");
        }
        // 外部资源删除成功后才移除元数据，失败时保留记录便于重试和审计。
        referenceDocumentRepository.deleteById(document.getId());
        referenceDocumentRepository.flush();
    }

    /**
     * 删除原始分析文档的向量、MinIO 对象和数据库记录。
     *
     * @param document 待删除的分析文档记录
     */
    private void deleteSourceDocumentRecordAndResources(Document document) {
        // 原始文档拥有自己的分析向量，先删除它再清理物理对象和数据库记录。
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
