package com.arohtea.business_service.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 分析任务实体及其持久化结果。
 *
 * <p>一行记录代表用户对一份文档发起的一次分析。状态字段描述任务生命周期，结果
 * 字段保存 AI Service 返回的结构化 JSON，进度字段用于页面展示。结果保留为文本是
 * 为了允许分析结果结构演进，对外输出时再由 DTO 验证 JSON 类型。</p>
 *
 * <p>状态迁移由服务层在行锁保护下完成，{@code version} 还用于 JPA 的乐观版本检查，
 * 帮助发现同一任务被多个线程同时更新的情况。</p>
 */
@Data
@Entity
@Table(name = "analysis_tasks")
public class AnalysisTask {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    /** 对外返回和跨服务消息使用的任务唯一 ID。 */
    private String id;

    @Version
    @Column(nullable = false, columnDefinition = "bigint default 0")
    /** JPA 版本号，用于检测并发写入冲突。 */
    private Long version = 0L;

    /** Business Service 文档 ID，不是 AI Service 的向量文档 ID。 */
    private String documentId;

    @Enumerated(EnumType.STRING)
    /** 当前生命周期状态，创建时先进入 PENDING，最终只能停在完成、失败或取消。 */
    private TaskStatus status = TaskStatus.PENDING;

    /** 本次分析模式；未明确指定 quick 时由服务层归一化为 deep。 */
    private String mode = "deep";

    @Column(columnDefinition = "TEXT")
    /** 创建任务时选中的参考资料库 ID JSON 快照。 */
    private String referenceLibraryIds = "[]";

    @Column(columnDefinition = "TEXT")
    /** 创建任务时的资料库名称 JSON 快照，避免目录重命名影响历史任务阅读。 */
    private String referenceLibraryNames = "[]";

    @Column(columnDefinition = "TEXT")
    /** AI 返回的论据链 JSON；任务失败或尚未完成时可以为空。 */
    private String argumentChain;

    @Column(columnDefinition = "TEXT")
    /** AI 返回的逻辑漏洞 JSON。 */
    private String logicFlaws;

    @Column(columnDefinition = "TEXT")
    /** AI 返回的交叉验证 JSON；快速模式可能没有该结果。 */
    private String crossValidation;

    /** 面向前端的 0~100 进度百分比。 */
    private Integer progress = 0;
    /** 当前处理步骤的可读文字，例如“检索参考资料”。 */
    private String currentStep;

    /** 任务第一次落库的时间，用于超时清理和历史排序。 */
    private LocalDateTime createdAt;
    /** 任务进入完成、失败或取消终态的时间；活动任务为空。 */
    private LocalDateTime completedAt;

    /**
     * 在首次持久化时记录任务创建时间。
     */
    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
    }
}
