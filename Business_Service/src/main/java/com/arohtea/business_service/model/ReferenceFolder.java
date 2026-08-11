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

/**
 * 资料库文件夹；同一资料库内名称唯一。
 */
@Data
@Entity
@Table(
        name = "reference_folders",
        uniqueConstraints = @UniqueConstraint(columnNames = {"libraryId", "name"})
)
public class ReferenceFolder {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    private String libraryId;
    private String name;
    private LocalDateTime createdAt;

    /**
     * 在首次持久化时记录文件夹创建时间。
     */
    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
    }
}
