package com.arohtea.business_service.dto;

import com.arohtea.business_service.model.Document;
import com.arohtea.business_service.model.AnalysisTask;

import java.time.LocalDateTime;

/**
 * 文档列表使用的轻量摘要，包含向量化和最新分析任务状态。
 *
 * <p>列表不返回全部分析 JSON，只返回用户决定下一步操作所需的状态：向量是否就绪、
 * 是否正在删除、最新任务的进度和步骤。这样列表页面无需下载大段分析结果。</p>
 *
 * @param id 文档 ID
 * @param filename 原始文件名
 * @param minioPath MinIO 对象路径
 * @param fileSize 文件字节数
 * @param aiDocId AI Service 文档 ID
 * @param createdAt 创建时间
 * @param analysisStatus 最新分析状态
 * @param analysisTaskId 最新分析任务 ID
 * @param analysisProgress 最新分析进度
 * @param analysisMode 最新分析模式
 * @param analysisCurrentStep 最新分析步骤
 * @param vectorStatus 向量化状态
 * @param deleting 是否正在删除
 */
public record DocumentSummaryResponse(
        String id,
        String filename,
        String minioPath,
        Long fileSize,
        String aiDocId,
        LocalDateTime createdAt,
        String analysisStatus,
        String analysisTaskId,
        Integer analysisProgress,
        String analysisMode,
        String analysisCurrentStep,
        String vectorStatus,
        boolean deleting
) {
    /**
     * 从文档实体和最新任务实体组装列表摘要。
     *
     * @param document 文档实体
     * @param task 最新任务，可为空
     * @return 前端文档列表使用的摘要
     */
    public static DocumentSummaryResponse from(Document document, AnalysisTask task) {
        // AI 文档 ID 是向量化完成的唯一标志；没有它时前端必须继续等待。
        String vectorStatus = document.getAiDocId() == null || document.getAiDocId().isBlank()
                ? "PROCESSING" : "READY";
        // 文档可能还没有分析任务，所以任务相关字段需要输出可识别的空值/零进度。
        return new DocumentSummaryResponse(
                document.getId(), document.getFilename(), document.getMinioPath(), document.getFileSize(),
                document.getAiDocId(), document.getCreatedAt(),
                task == null || task.getStatus() == null ? null : task.getStatus().name(),
                task == null ? null : task.getId(),
                task == null ? 0 : task.getProgress(),
                task == null ? null : task.getMode(),
                task == null ? null : task.getCurrentStep(),
                vectorStatus,
                document.isDeleting()
        );
    }
}
