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

    public ReferenceLibrary createLibrary(String name) {
        String normalizedName = name == null ? "" : name.trim();
        if (normalizedName.isEmpty()) {
            throw new IllegalArgumentException("资料集名称不能为空");
        }
        ReferenceLibrary library = new ReferenceLibrary();
        library.setName(normalizedName);
        return referenceLibraryRepository.save(library);
    }

    public List<ReferenceLibrary> listLibraries() {
        return referenceLibraryRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    public ReferenceLibrary getLibrary(String id) {
        return referenceLibraryRepository.findById(id).orElse(null);
    }

    public List<ReferenceDocument> listDocuments(String libraryId) {
        return referenceDocumentRepository.findByLibraryIdOrderByCreatedAtDesc(libraryId);
    }

    public List<ReferenceFolder> listFolders(String libraryId) {
        return referenceFolderRepository.findByLibraryIdOrderByCreatedAtAsc(libraryId);
    }

    public List<ReferenceCategory> listCategories(String libraryId) {
        return referenceCategoryRepository.findByLibraryIdOrderByCreatedAtAsc(libraryId);
    }

    @Transactional
    public ReferenceFolder createFolder(String libraryId, String name) {
        ensureLibraryExists(libraryId);
        return referenceArchiveService.ensureFolder(libraryId, name);
    }

    @Transactional
    public ReferenceCategory createCategory(String libraryId, String name) {
        ensureLibraryExists(libraryId);
        return referenceArchiveService.ensureCategory(libraryId, name);
    }

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

    @Transactional
    public void deleteFolder(String folderId) {
        if (referenceDocumentRepository.countByFolderId(folderId) > 0) {
            throw new IllegalStateException("文件夹仍被资料引用，请先调整文档挂载");
        }
        referenceFolderRepository.deleteById(folderId);
    }

    @Transactional
    public void deleteCategory(String categoryId) {
        if (referenceDocumentRepository.countByCategoryId(categoryId) > 0) {
            throw new IllegalStateException("分类仍被资料引用，请先调整文档挂载");
        }
        referenceCategoryRepository.deleteById(categoryId);
    }

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

    public ReferenceDocument uploadDocument(String libraryId, MultipartFile file) throws Exception {
        ReferenceLibrary library = getLibrary(libraryId);
        if (library == null) {
            throw new IllegalArgumentException("资料集不存在");
        }

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
        var settings = settingsService.get();
        CompletableFuture.runAsync(() -> {
            try {
                String aiDocId = aiServiceClient.uploadDocument(
                        fileBytes,
                        file.getOriginalFilename(),
                        settings.getApiKey(),
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
            }
        });

        return saved;
    }

    @Transactional
    public void deleteDocument(String documentId) throws Exception {
        referenceArchiveService.deleteReferenceDocumentWithLinkedSource(documentId);
    }

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

    private void ensureBucket() throws Exception {
        if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }

    private void ensureLibraryExists(String libraryId) {
        if (getLibrary(libraryId) == null) {
            throw new IllegalArgumentException("资料集不存在");
        }
    }

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

    private String normalizeName(String value, String message) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(message);
        }
        return normalized;
    }
}
