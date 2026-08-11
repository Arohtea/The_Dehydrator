package com.arohtea.business_service.service;

import com.arohtea.business_service.client.AiServiceClient;
import com.arohtea.business_service.model.ReferenceDocument;
import com.arohtea.business_service.model.ReferenceLibrary;
import com.arohtea.business_service.model.ReferenceFolder;
import com.arohtea.business_service.model.ReferenceCategory;
import com.arohtea.business_service.repository.ReferenceCategoryRepository;
import com.arohtea.business_service.repository.ReferenceDocumentRepository;
import com.arohtea.business_service.repository.ReferenceFolderRepository;
import com.arohtea.business_service.repository.ReferenceLibraryRepository;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 参考资料库、文件夹、分类和参考文档的业务编排服务。
 *
 * <p>数据库记录负责目录和元数据，MinIO 保存原文件，AI Service/Qdrant 负责
 * 异步解析与向量化；跨存储操作失败时由归档服务执行补偿清理。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReferenceLibraryService {

    private final ReferenceLibraryRepository referenceLibraryRepository;
    private final ReferenceDocumentRepository referenceDocumentRepository;
    private final ReferenceFolderRepository referenceFolderRepository;
    private final ReferenceCategoryRepository referenceCategoryRepository;
    private final MinioClient minioClient;
    private final AiServiceClient aiServiceClient;
    private final SystemSettingsService settingsService;
    private final ReferenceArchiveService referenceArchiveService;

    @Value("${minio.bucket}")
    private String bucket;

    /**
     * 创建普通参考资料库。
     *
     * @param name 资料库名称
     * @return 已保存的资料库
     * @throws IllegalArgumentException 名称为空
     */
    public ReferenceLibrary createLibrary(String name) {
        String normalizedName = name == null ? "" : name.trim();
        if (normalizedName.isEmpty()) {
            throw new IllegalArgumentException("资料集名称不能为空");
        }
        ReferenceLibrary library = new ReferenceLibrary();
        library.setName(normalizedName);
        return referenceLibraryRepository.save(library);
    }

    /**
     * 按创建时间倒序列出资料库。
     *
     * @return 资料库列表
     */
    public List<ReferenceLibrary> listLibraries() {
        return referenceLibraryRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    /**
     * 查询资料库。
     *
     * @param id 资料库 ID
     * @return 资料库；不存在时返回 null
     */
    public ReferenceLibrary getLibrary(String id) {
        return referenceLibraryRepository.findById(id).orElse(null);
    }

    /**
     * 列出资料库中的参考文档。
     *
     * @param libraryId 资料库 ID
     * @return 按创建时间倒序排列的文档列表
     */
    public List<ReferenceDocument> listDocuments(String libraryId) {
        return referenceDocumentRepository.findByLibraryIdOrderByCreatedAtDesc(libraryId);
    }

    /**
     * 列出资料库中的文件夹。
     *
     * @param libraryId 资料库 ID
     * @return 按创建时间正序排列的文件夹列表
     */
    public List<ReferenceFolder> listFolders(String libraryId) {
        return referenceFolderRepository.findByLibraryIdOrderByCreatedAtAsc(libraryId);
    }

    /**
     * 列出资料库中的分类。
     *
     * @param libraryId 资料库 ID
     * @return 按创建时间正序排列的分类列表
     */
    public List<ReferenceCategory> listCategories(String libraryId) {
        return referenceCategoryRepository.findByLibraryIdOrderByCreatedAtAsc(libraryId);
    }

    /**
     * 创建资料库内的文件夹，并复用同名记录。
     *
     * @param libraryId 资料库 ID
     * @param name 文件夹名称
     * @return 已存在或新建的文件夹
     */
    @Transactional
    public ReferenceFolder createFolder(String libraryId, String name) {
        ensureLibraryExists(libraryId);
        return referenceArchiveService.ensureFolder(libraryId, name);
    }

    /**
     * 创建资料库内的分类，并复用同名记录。
     *
     * @param libraryId 资料库 ID
     * @param name 分类名称
     * @return 已存在或新建的分类
     */
    @Transactional
    public ReferenceCategory createCategory(String libraryId, String name) {
        ensureLibraryExists(libraryId);
        return referenceArchiveService.ensureCategory(libraryId, name);
    }

    /**
     * 重命名文件夹并保持同一资料库内名称唯一。
     *
     * @param folderId 文件夹 ID
     * @param name 新名称
     * @return 更新后的文件夹；不存在时返回 null
     * @throws IllegalArgumentException 名称为空或同库内重名
     */
    @Transactional
    public ReferenceFolder renameFolder(String folderId, String name) {
        ReferenceFolder folder = referenceFolderRepository.findById(folderId).orElse(null);
        if (folder == null) {
            return null;
        }
        String normalizedName = normalizeName(name, "文件夹名称不能为空");
        referenceFolderRepository.findByLibraryIdAndName(folder.getLibraryId(), normalizedName)
                .filter(existing -> !existing.getId().equals(folderId))
                .ifPresent(existing -> {
                    throw new IllegalArgumentException("文件夹名称已存在");
                });
        folder.setName(normalizedName);
        return referenceFolderRepository.save(folder);
    }

    /**
     * 重命名分类并保持同一资料库内名称唯一。
     *
     * @param categoryId 分类 ID
     * @param name 新名称
     * @return 更新后的分类；不存在时返回 null
     * @throws IllegalArgumentException 名称为空或同库内重名
     */
    @Transactional
    public ReferenceCategory renameCategory(String categoryId, String name) {
        ReferenceCategory category = referenceCategoryRepository.findById(categoryId).orElse(null);
        if (category == null) {
            return null;
        }
        String normalizedName = normalizeName(name, "分类名称不能为空");
        referenceCategoryRepository.findByLibraryIdAndName(category.getLibraryId(), normalizedName)
                .filter(existing -> !existing.getId().equals(categoryId))
                .ifPresent(existing -> {
                    throw new IllegalArgumentException("分类名称已存在");
                });
        category.setName(normalizedName);
        return referenceCategoryRepository.save(category);
    }

    /**
     * 删除未被任何资料引用的文件夹。
     *
     * @param folderId 文件夹 ID
     * @throws IllegalStateException 文件夹仍被资料引用
     */
    @Transactional
    public void deleteFolder(String folderId) {
        if (referenceDocumentRepository.countByFolderId(folderId) > 0) {
            throw new IllegalStateException("文件夹仍被资料引用，请先调整文档挂载");
        }
        referenceFolderRepository.deleteById(folderId);
    }

    /**
     * 删除未被任何资料引用的分类。
     *
     * @param categoryId 分类 ID
     * @throws IllegalStateException 分类仍被资料引用
     */
    @Transactional
    public void deleteCategory(String categoryId) {
        if (referenceDocumentRepository.countByCategoryId(categoryId) > 0) {
            throw new IllegalStateException("分类仍被资料引用，请先调整文档挂载");
        }
        referenceCategoryRepository.deleteById(categoryId);
    }

    /**
     * 更新参考资料展示名称及其所属目录。
     *
     * @param documentId 参考资料 ID
     * @param displayName 展示名称
     * @param folderId 目标文件夹 ID，可为空
     * @param categoryId 目标分类 ID，可为空
     * @return 更新后的资料；不存在时返回 null
     * @throws IllegalArgumentException 名称为空或目录不属于当前资料库
     */
    @Transactional
    public ReferenceDocument updateDocument(String documentId, String displayName, String folderId, String categoryId) {
        ReferenceDocument document = referenceDocumentRepository.findById(documentId).orElse(null);
        if (document == null) {
            return null;
        }

        String normalizedDisplayName = normalizeName(displayName, "资料名称不能为空");
        document.setDisplayName(normalizedDisplayName);
        document.setFolderId(validateFolderOwnership(document.getLibraryId(), folderId));
        document.setCategoryId(validateCategoryOwnership(document.getLibraryId(), categoryId));
        return referenceDocumentRepository.save(document);
    }

    /**
     * 校验数据库向量配置后保存参考资料，并异步完成向量化。
     *
     * @param libraryId 目标参考资料集 ID
     * @param file 上传文件
     * @return 已保存的参考资料
     * @throws Exception 文件存储或读取失败
     *
     * <p>数据库记录先保存为未向量化状态，后台任务完成后再回写 AI 文档 ID；若
     * 资料在异步期间被删除，则立即回收已经创建的向量。</p>
     */
    public ReferenceDocument uploadDocument(String libraryId, MultipartFile file) throws Exception {
        ReferenceLibrary library = getLibrary(libraryId);
        if (library == null) {
            throw new IllegalArgumentException("资料集不存在");
        }

        var settings = settingsService.get();
        var vectorModel = settingsService.requireVectorModelConfig(settings);
        ensureBucket();

        String path = "reference/" + libraryId + "/" + UUID.randomUUID() + "/" + file.getOriginalFilename();
        byte[] fileBytes = file.getBytes();

        minioClient.putObject(PutObjectArgs.builder()
                .bucket(bucket)
                .object(path)
                .stream(new ByteArrayInputStream(fileBytes), fileBytes.length, -1)
                .contentType(file.getContentType())
                .build());

        ReferenceDocument document = new ReferenceDocument();
        document.setLibraryId(libraryId);
        document.setFilename(file.getOriginalFilename());
        document.setDisplayName(file.getOriginalFilename());
        document.setMinioPath(path);
        document.setFileSize(file.getSize());
        ReferenceDocument saved = referenceDocumentRepository.save(document);

        String documentId = saved.getId();
        CompletableFuture.runAsync(() -> {
            String aiDocId = null;
            try {
                aiDocId = aiServiceClient.uploadDocument(
                        fileBytes,
                        file.getOriginalFilename(),
                        vectorModel,
                        settings.getChunkSize(),
                        settings.getChunkOverlap(),
                        "reference_document",
                        libraryId
                );
                ReferenceDocument current = referenceDocumentRepository.findById(documentId).orElse(null);
                if (current == null) {
                    aiServiceClient.deleteDocument(aiDocId);
                    log.info("参考资料已删除，回收参考向量: {} -> {}", documentId, aiDocId);
                    return;
                }
                current.setAiDocId(aiDocId);
                referenceDocumentRepository.save(current);
                log.info("参考资料向量化完成: {} -> {}", documentId, aiDocId);
            } catch (Exception e) {
                log.error("参考资料向量化失败: {}", documentId, e);
                if (aiDocId != null && !aiDocId.isBlank()) {
                    try {
                        aiServiceClient.deleteDocument(aiDocId);
                    } catch (Exception cleanupException) {
                        log.warn("清理失败的参考向量失败: {}", documentId, cleanupException);
                    }
                }
                try {
                    referenceArchiveService.deleteReferenceDocumentWithLinkedSource(documentId);
                } catch (Exception cleanupException) {
                    log.warn("清理失败的参考资料资源失败: {}", documentId, cleanupException);
                }
            }
        });

        return saved;
    }

    /**
     * 删除独立参考资料；系统自动归档镜像必须通过原始文档入口删除。
     *
     * @param documentId 参考资料 ID
     * @throws Exception 外部资源删除失败
     * @throws IllegalStateException 目标资料是系统自动归档镜像
     */
    @Transactional
    public void deleteDocument(String documentId) throws Exception {
        ReferenceDocument document = referenceDocumentRepository.findById(documentId).orElse(null);
        if (document != null && document.getSourceDocumentId() != null && !document.getSourceDocumentId().isBlank()) {
            throw new IllegalStateException("系统自动归档资料不能直接删除，请删除原始文档");
        }
        referenceArchiveService.deleteReferenceDocumentWithLinkedSource(documentId);
        log.info("审计: 删除参考资料 documentId={}", documentId);
    }

    /**
     * 删除空的普通资料库及其目录。
     *
     * @param libraryId 资料库 ID
     * @throws IllegalStateException 资料库是系统库或仍包含文档
     */
    @Transactional
    public void deleteLibrary(String libraryId) {
        ReferenceLibrary library = getLibrary(libraryId);
        if (library == null) {
            return;
        }
        if (ReferenceLibrary.AUTO_ANALYSIS_ARCHIVE_KEY.equals(library.getSystemKey())) {
            throw new IllegalStateException("系统资料库不允许删除");
        }
        if (referenceDocumentRepository.countByLibraryId(libraryId) > 0) {
            throw new IllegalStateException("资料集非空，请先删除其中的资料文件");
        }
        referenceFolderRepository.deleteByLibraryId(libraryId);
        referenceCategoryRepository.deleteByLibraryId(libraryId);
        referenceLibraryRepository.deleteById(libraryId);
    }

    /**
     * 确保 MinIO 目标 bucket 存在。
     *
     * @throws Exception MinIO 查询或创建 bucket 失败
     */
    private void ensureBucket() throws Exception {
        if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }

    /**
     * 校验资料库 ID 存在。
     *
     * @param libraryId 资料库 ID
     * @throws IllegalArgumentException 资料库不存在
     */
    private void ensureLibraryExists(String libraryId) {
        if (getLibrary(libraryId) == null) {
            throw new IllegalArgumentException("资料集不存在");
        }
    }

    /**
     * 校验文件夹存在且属于当前资料库。
     *
     * @param libraryId 当前资料库 ID
     * @param folderId 待绑定的文件夹 ID，可为空
     * @return 合法的文件夹 ID；输入为空时返回 null
     * @throws IllegalArgumentException 文件夹不存在或跨资料库引用
     */
    private String validateFolderOwnership(String libraryId, String folderId) {
        if (folderId == null || folderId.isBlank()) {
            return null;
        }
        ReferenceFolder folder = referenceFolderRepository.findById(folderId).orElseThrow(
                () -> new IllegalArgumentException("文件夹不存在")
        );
        if (!libraryId.equals(folder.getLibraryId())) {
            throw new IllegalArgumentException("文件夹不属于当前资料集");
        }
        return folderId;
    }

    /**
     * 校验分类存在且属于当前资料库。
     *
     * @param libraryId 当前资料库 ID
     * @param categoryId 待绑定的分类 ID，可为空
     * @return 合法的分类 ID；输入为空时返回 null
     * @throws IllegalArgumentException 分类不存在或跨资料库引用
     */
    private String validateCategoryOwnership(String libraryId, String categoryId) {
        if (categoryId == null || categoryId.isBlank()) {
            return null;
        }
        ReferenceCategory category = referenceCategoryRepository.findById(categoryId).orElseThrow(
                () -> new IllegalArgumentException("分类不存在")
        );
        if (!libraryId.equals(category.getLibraryId())) {
            throw new IllegalArgumentException("分类不属于当前资料集");
        }
        return categoryId;
    }

    /**
     * 清理目录或展示名称并拒绝空值。
     *
     * @param value 原始名称
     * @param message 空值时的业务错误
     * @return 去除首尾空白后的名称
     * @throws IllegalArgumentException 名称为空
     */
    private String normalizeName(String value, String message) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(message);
        }
        return normalized;
    }
}
