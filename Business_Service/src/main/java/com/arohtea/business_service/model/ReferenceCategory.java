package com.arohtea.business_service.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(
        name = "reference_categories",
        uniqueConstraints = @UniqueConstraint(columnNames = {"libraryId", "name"})
)
public class ReferenceCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    private String libraryId;
    private String name;
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
    }
}
