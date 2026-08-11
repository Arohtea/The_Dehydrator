package com.arohtea.business_service.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 用户上传的分析文档元数据。
 *
 * <p>数据库只保存文件名、MinIO 路径和资源状态，不保存文件正文。上传后
 * {@code aiDocId} 为空表示向量还没有准备好；后台完成向量化后再回填。删除流程
 * 先把 {@code deleting} 设为 true，阻止新的分析和旧异步回调继续使用这份文档。</p>
 */
@Data
@Entity
@Table(name = "documents")
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    /** Business Service 内部的文档唯一 ID。 */
    private String id;

    /** 用户上传时的原始文件名。 */
    private String filename;
    /** MinIO bucket 内的对象路径，数据库不直接保存文件内容。 */
    private String minioPath;
    /** 文件大小，用于列表展示和上传审计。 */
    private Long fileSize;

    @Column(columnDefinition = "TEXT")
    /** AI Service/Qdrant 中对应的向量文档 ID，向量化完成前为空。 */
    private String aiDocId;

    /** 删除流程等待分析服务确认期间，禁止再次启动分析和接受旧异步回写。 */
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean deleting = false;

    /** 文档元数据首次保存时间。 */
    private LocalDateTime createdAt;

    /**
     * 在首次持久化时记录文档创建时间。
     */
    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
    }
}
