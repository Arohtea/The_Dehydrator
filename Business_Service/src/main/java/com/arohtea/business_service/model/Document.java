package com.arohtea.business_service.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 用户上传的分析文档元数据。
 *
 * <p>`aiDocId` 在后台向量化完成后异步回填，`deleting` 用于阻止删除期间新的
 * 分析启动和旧回调复活文档。</p>
 */
@Data
@Entity
@Table(name = "documents")
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    private String filename;
    private String minioPath;
    private Long fileSize;

    @Column(columnDefinition = "TEXT")
    private String aiDocId;

    /** 删除流程等待分析服务确认期间，禁止再次启动分析。 */
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean deleting = false;

    private LocalDateTime createdAt;

    /**
     * 在首次持久化时记录文档创建时间。
     */
    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
    }
}
