package com.arohtea.business_service.service;

import com.arohtea.business_service.client.AiServiceClient;
import com.arohtea.business_service.model.ReferenceDocument;
import com.arohtea.business_service.model.ReferenceLibrary;
import com.arohtea.business_service.repository.ReferenceDocumentRepository;
import com.arohtea.business_service.repository.ReferenceLibraryRepository;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
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
    private final MinioClient minioClient;
    private final AiServiceClient aiServiceClient;
    private final SystemSettingsService settingsService;

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
                saved.setAiDocId(aiDocId);
                referenceDocumentRepository.save(saved);
                log.info("参考资料向量化完成: {} -> {}", documentId, aiDocId);
            } catch (Exception e) {
                log.error("参考资料向量化失败: {}", documentId, e);
            }
        });

        return saved;
    }

    @Transactional
    public void deleteDocument(String documentId) throws Exception {
        ReferenceDocument document = referenceDocumentRepository.findById(documentId).orElse(null);
        if (document == null) {
            return;
        }
        String minioPath = document.getMinioPath();
        String aiDocId = document.getAiDocId();
        referenceDocumentRepository.deleteById(documentId);
        referenceDocumentRepository.flush();
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(minioPath)
                    .build());
        } catch (Exception e) {
            log.warn("删除参考资料MinIO文件失败: {}", e.getMessage());
        }
        if (aiDocId != null) {
            try {
                aiServiceClient.deleteDocument(aiDocId);
            } catch (Exception e) {
                log.warn("删除参考资料向量失败: {}", e.getMessage());
            }
        }
    }

    @Transactional
    public void deleteLibrary(String libraryId) {
        ReferenceLibrary library = getLibrary(libraryId);
        if (library == null) {
            return;
        }
        if (referenceDocumentRepository.countByLibraryId(libraryId) > 0) {
            throw new IllegalStateException("资料集非空，请先删除其中的资料文件");
        }
        referenceLibraryRepository.deleteById(libraryId);
    }

    private void ensureBucket() throws Exception {
        if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }
}
