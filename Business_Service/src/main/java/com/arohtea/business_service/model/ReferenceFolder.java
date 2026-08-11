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
 *
 * <p>文件夹只提供组织关系，不拥有文件对象。资料记录通过 folderId 指向它，删除
 * 前由服务层检查引用数量。</p>
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

    /** 父资料库 ID。 */
    private String libraryId;
    /** 文件夹展示名称，同一资料库内不可重复。 */
    private String name;
    /** 文件夹首次创建时间，用于稳定排序。 */
    private LocalDateTime createdAt;

    /**
     * 在首次持久化时记录文件夹创建时间。
     */
    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
    }
}
