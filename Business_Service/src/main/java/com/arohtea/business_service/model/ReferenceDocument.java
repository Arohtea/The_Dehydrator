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
 * <p>{@code sourceDocumentId} 为空时，这是一份用户独立上传的参考资料，自己拥有
 * MinIO 对象和 AI 向量；非空时，它是分析文档的自动归档镜像，共享原文对象但使用
 * 独立的 AI 文档 ID。这个区别决定删除时应该释放哪些资源。</p>
 */
@Data
@Entity
@Table(name = "reference_documents")
public class ReferenceDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    /** 资料库页面使用的参考文档记录 ID。 */
    private String id;

    /** 所属参考资料库 ID。 */
    private String libraryId;
    /** 文件上传时的原始文件名。 */
    private String filename;
    /** 用户在资料库中看到的名称，可独立于原始文件名修改。 */
    private String displayName;
    /** 所属文件夹 ID，可为空表示未挂载目录。 */
    private String folderId;
    /** 所属分类 ID，可为空表示未分类。 */
    private String categoryId;
    /** 自动归档镜像对应的原始分析文档 ID，独立资料为空。 */
    private String sourceDocumentId;
    /** MinIO 对象路径；镜像与原始文档可能共享该路径。 */
    private String minioPath;
    /** 文件大小，用于资料库列表展示。 */
    private Long fileSize;

    @Column(columnDefinition = "TEXT")
    /** AI Service 中该参考资料的向量文档 ID，向量化完成前为空。 */
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
