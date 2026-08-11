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
 * 参考资料库目录实体。
 *
 * <p>普通资料库由管理员创建和删除；带 {@code systemKey} 的资料库由系统流程维护。
 * 自动归档库使用固定 Key 识别，不能按展示名称查找或按普通库删除。</p>
 */
@Data
@Entity
@Table(name = "reference_libraries")
public class ReferenceLibrary {

    /** 自动归档库的稳定业务标识。 */
    public static final String AUTO_ANALYSIS_ARCHIVE_KEY = "AUTO_ANALYSIS_ARCHIVE";
    /** 自动归档库首次创建时的默认展示名称。 */
    public static final String AUTO_ANALYSIS_ARCHIVE_NAME = "分析论文资料库";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    /** 资料库唯一 ID，作为目录和文档的父级引用。 */
    private String id;

    /** 面向管理员展示的资料库名称。 */
    private String name;

    @Column(unique = true)
    /** 系统库稳定标识；普通用户创建的资料库为空。 */
    private String systemKey;

    /** 资料库首次创建时间。 */
    private LocalDateTime createdAt;

    /**
     * 在首次持久化时记录资料库创建时间。
     */
    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
    }
}
