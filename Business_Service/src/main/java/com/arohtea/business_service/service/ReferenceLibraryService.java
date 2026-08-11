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
 * <p>资料库的目录结构和文件元数据保存在数据库，原始文件保存在 MinIO，AI
 * Service/Qdrant 保存可检索的向量。这个服务负责把用户的一个上传、重命名或删除
 * 动作拆成多个存储操作，并把跨存储失败交给归档服务做补偿。</p>
 *
 * <p>普通参考资料可以单独删除；分析文档自动生成的镜像有
 * {@code sourceDocumentId}，必须回到原始文档删除入口，防止目录记录和共享资源
 * 的所有权关系被破坏。</p>
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
     * <p>普通库不设置系统标识，因此以后可以由管理员删除；系统自动归档库使用
     * 固定 {@code systemKey} 创建，走 {@link ReferenceArchiveService} 的专用路径。</p>
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
     * <p>实际的“存在则返回、否则创建”逻辑集中在归档服务中，普通资料库和系统
     * 自动归档库使用同一套幂等规则，避免重复点击创建一堆同名目录。</p>
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
     * <p>文件夹只是目录节点，资料记录仍保存着 folderId。删除前先统计引用数量，
     * 让用户先调整资料挂载关系，避免删除后出现指向不存在目录的记录。</p>
     *
     * @param folderId 文件夹 ID
     * @throws IllegalStateException 文件夹仍被资料引用
     */
    @Transactional
    public void deleteFolder(String folderId) {
        // 先检查引用再删除目录，保证现有资料的 folderId 仍然有对应目标。
        if (referenceDocumentRepository.countByFolderId(folderId) > 0) {
            throw new IllegalStateException("文件夹仍被资料引用，请先调整文档挂载");
        }
        referenceFolderRepository.deleteById(folderId);
    }

    /**
     * 删除未被任何资料引用的分类。
     *
     * <p>分类和文件夹采用相同的引用保护策略；不把“删除目录”默认为“自动把资料
     * 移到其他目录”，避免静默改变用户组织方式。</p>
     *
     * @param categoryId 分类 ID
     * @throws IllegalStateException 分类仍被资料引用
     */
    @Transactional
    public void deleteCategory(String categoryId) {
        // 分类也必须先解除引用，不能让资料保留一个已经不存在的 categoryId。
        if (referenceDocumentRepository.countByCategoryId(categoryId) > 0) {
            throw new IllegalStateException("分类仍被资料引用，请先调整文档挂载");
        }
        referenceCategoryRepository.deleteById(categoryId);
    }

    /**
     * 更新参考资料展示名称及其所属目录。
     *
     * <p>文件夹和分类 ID 不能只检查“记录存在”，还要检查它们属于当前资料库。
     * 这一步阻止一个资料库的文档被错误挂到另一个资料库的目录下。</p>
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

        // 展示名称允许和原始文件名不同，但不能保存空白名称。
        String normalizedDisplayName = normalizeName(displayName, "资料名称不能为空");
        document.setDisplayName(normalizedDisplayName);
        // 空目录 ID 表示移回未分类状态；非空 ID 必须通过归属校验。
        document.setFolderId(validateFolderOwnership(document.getLibraryId(), folderId));
        document.setCategoryId(validateCategoryOwnership(document.getLibraryId(), categoryId));
        return referenceDocumentRepository.save(document);
    }

    /**
     * 校验数据库向量配置后保存参考资料，并异步完成向量化。
     *
     * <p>同步阶段依次完成资料库校验、设置快照、MinIO 上传和数据库记录保存；
     * 异步阶段把同一份字节和配置送到 AI Service，成功后回写 aiDocId。因为后台
     * 线程可能晚于用户删除资料，回写前会重新查询记录；如果记录不存在，就回收
     * 已创建的远程向量。</p>
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
        // 先验证目标资料库，避免把文件写入对象存储后才发现没有可关联的目录记录。
        ReferenceLibrary library = getLibrary(libraryId);
        if (library == null) {
            throw new IllegalArgumentException("资料集不存在");
        }

        // 冻结向量模型和分块参数，保证一次上传始终使用同一套处理配置。
        var settings = settingsService.get();
        var vectorModel = settingsService.requireVectorModelConfig(settings);
        ensureBucket();

        // 对象路径带资料库和随机目录，既方便按库排查，也避免同名文件覆盖。
        String path = "reference/" + libraryId + "/" + UUID.randomUUID() + "/" + file.getOriginalFilename();
        byte[] fileBytes = file.getBytes();

        // 先把物理文件保存下来；后面的数据库记录只引用这个路径，不再保存整份文件内容。
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
        // 保存未完成向量化的元数据，aiDocId 暂时为空，前端据此显示“处理中”。
        ReferenceDocument saved = referenceDocumentRepository.save(document);

        String documentId = saved.getId();
        CompletableFuture.runAsync(() -> {
            String aiDocId = null;
            try {
                // 使用上传时快照调用 AI Service，避免管理员改设置后同一批数据参数不一致。
                aiDocId = aiServiceClient.uploadDocument(
                        fileBytes,
                        file.getOriginalFilename(),
                        vectorModel,
                        settings.getChunkSize(),
                        settings.getChunkOverlap(),
                        "reference_document",
                        libraryId
                );
                // 回写前重新查询，识别用户在异步处理期间已经删除的资料。
                ReferenceDocument current = referenceDocumentRepository.findById(documentId).orElse(null);
                if (current == null) {
                    // 数据库记录已不存在，但向量已经创建，必须立刻回收远程资源。
                    aiServiceClient.deleteDocument(aiDocId);
                    log.info("参考资料已删除，回收参考向量: {} -> {}", documentId, aiDocId);
                    return;
                }
                // 只有记录仍存在才绑定远程 ID；绑定后分析任务才能把资料加入检索范围。
                current.setAiDocId(aiDocId);
                referenceDocumentRepository.save(current);
                log.info("参考资料向量化完成: {} -> {}", documentId, aiDocId);
            } catch (Exception e) {
                // 后台异常无法返回给原始 HTTP 请求，因此记录失败并进入补偿清理路径。
                log.error("参考资料向量化失败: {}", documentId, e);
                if (aiDocId != null && !aiDocId.isBlank()) {
                    // 已经拿到 AI ID 时先清理向量，防止失败重试后积累孤儿向量。
                    try {
                        aiServiceClient.deleteDocument(aiDocId);
                    } catch (Exception cleanupException) {
                        log.warn("清理失败的参考向量失败: {}", documentId, cleanupException);
                    }
                }
                // 归档服务会根据是否为镜像决定是否删除物理对象，避免补偿时误删共享文件。
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
     * <p>这里先拦截镜像，再委托归档服务清理向量、对象和数据库记录。控制器只负责
     * 把异常转换为 HTTP 响应，不直接触碰任一外部存储。</p>
     *
     * @param documentId 参考资料 ID
     * @throws Exception 外部资源删除失败
     * @throws IllegalStateException 目标资料是系统自动归档镜像
     */
    @Transactional
    public void deleteDocument(String documentId) throws Exception {
        ReferenceDocument document = referenceDocumentRepository.findById(documentId).orElse(null);
        // 镜像的物理文件由原始文档拥有，单独删除会破坏原文和其他镜像的共享关系。
        if (document != null && document.getSourceDocumentId() != null && !document.getSourceDocumentId().isBlank()) {
            throw new IllegalStateException("系统自动归档资料不能直接删除，请删除原始文档");
        }
        referenceArchiveService.deleteReferenceDocumentWithLinkedSource(documentId);
        log.info("审计: 删除参考资料 documentId={}", documentId);
    }

    /**
     * 删除空的普通资料库及其目录。
     *
     * <p>系统库是分析流程的固定落点，不能删除；普通库也必须先为空，再删除其
     * 文件夹和分类，最后删除库本身，避免留下没有父资料库的目录记录。</p>
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
        // 固定系统库由上传归档流程依赖，删除它会让后续上传无法建立默认镜像。
        if (ReferenceLibrary.AUTO_ANALYSIS_ARCHIVE_KEY.equals(library.getSystemKey())) {
            throw new IllegalStateException("系统资料库不允许删除");
        }
        // 先确认资料为空，再按父子顺序清理目录和资料库记录。
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
     * <p>开发环境可能首次启动时没有预先创建 bucket，因此上传前做一次幂等检查；
     * 已存在时不重复创建。</p>
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
