package com.arohtea.business_service.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Column;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 参考资料库目录实体；带系统 Key 的资料库由系统维护，不能按普通库删除。
 */
@Data
@Entity
@Table(name = "reference_libraries")
public class ReferenceLibrary {

    public static final String AUTO_ANALYSIS_ARCHIVE_KEY = "AUTO_ANALYSIS_ARCHIVE";
    public static final String AUTO_ANALYSIS_ARCHIVE_NAME = "分析论文资料库";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    private String name;

    @Column(unique = true)
    private String systemKey;

    private LocalDateTime createdAt;

    /**
     * 在首次持久化时记录资料库创建时间。
     */
    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
    }
}
