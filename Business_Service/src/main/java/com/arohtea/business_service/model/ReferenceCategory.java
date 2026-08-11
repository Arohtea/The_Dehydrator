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
 * 资料库分类；同一资料库内名称唯一。
 *
 * <p>分类和文件夹都是资料的组织标签，但分类可以表达跨文件夹的主题关系；资料
 * 通过 categoryId 引用它，删除前必须先解除所有引用。</p>
 */
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

    /** 父资料库 ID。 */
    private String libraryId;
    /** 分类展示名称，同一资料库内不可重复。 */
    private String name;
    /** 分类首次创建时间，用于稳定排序。 */
    private LocalDateTime createdAt;

    /**
     * 在首次持久化时记录分类创建时间。
     */
    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
    }
}
