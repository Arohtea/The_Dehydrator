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

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
    }
}
