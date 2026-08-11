package com.arohtea.business_service.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 参考资料库中的文档元数据。
 *
 * <p>`sourceDocumentId` 非空时表示它是分析文档的自动归档镜像，MinIO 对象可与
 * 原始文档复用，但 Qdrant 向量使用独立的 AI 文档 ID。</p>
 */
@Data
@Entity
@Table(name = "reference_documents")
public class ReferenceDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    private String libraryId;
    private String filename;
    private String displayName;
    private String folderId;
    private String categoryId;
    private String sourceDocumentId;
    private String minioPath;
    private Long fileSize;

    @Column(columnDefinition = "TEXT")
    private String aiDocId;

    private LocalDateTime createdAt;

    /**
     * 在首次持久化时记录参考资料创建时间。
     */
    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
    }
}
