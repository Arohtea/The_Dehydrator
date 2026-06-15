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

@Data
@Entity
@Table(name = "reference_documents")
public class ReferenceDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    private String libraryId;
    private String filename;
    private String minioPath;
    private Long fileSize;

    @Column(columnDefinition = "TEXT")
    private String aiDocId;

    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
    }
}
