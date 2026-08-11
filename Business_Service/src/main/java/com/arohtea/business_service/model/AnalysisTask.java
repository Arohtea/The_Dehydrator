package com.arohtea.business_service.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 分析任务实体及其持久化结果。
 *
 * <p>结果字段以 JSON 文本保存，避免任务处理中间状态频繁改变表结构；对外响应
 * 由 DTO 负责安全解析。状态迁移由服务层在行锁保护下完成。</p>
 */
@Data
@Entity
@Table(name = "analysis_tasks")
public class AnalysisTask {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Version
    @Column(nullable = false, columnDefinition = "bigint default 0")
    private Long version = 0L;

    private String documentId;

    @Enumerated(EnumType.STRING)
    private TaskStatus status = TaskStatus.PENDING;

    private String mode = "deep";

    @Column(columnDefinition = "TEXT")
    private String referenceLibraryIds = "[]";

    @Column(columnDefinition = "TEXT")
    private String referenceLibraryNames = "[]";

    @Column(columnDefinition = "TEXT")
    private String argumentChain;

    @Column(columnDefinition = "TEXT")
    private String logicFlaws;

    @Column(columnDefinition = "TEXT")
    private String crossValidation;

    private Integer progress = 0;
    private String currentStep;

    private LocalDateTime createdAt;
    private LocalDateTime completedAt;

    /**
     * 在首次持久化时记录任务创建时间。
     */
    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
    }
}
