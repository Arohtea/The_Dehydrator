package com.arohtea.business_service.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

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
    @Column(nullable = false)
    private boolean deleting = false;

    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
    }
}
