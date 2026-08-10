package com.arohtea.business_service.dto;

import com.arohtea.business_service.model.Document;
import com.arohtea.business_service.model.AnalysisTask;

import java.time.LocalDateTime;

/** 文档列表使用的轻量摘要，包含向量化和最新分析任务状态。 */
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
    public static DocumentSummaryResponse from(Document document, AnalysisTask task) {
        String vectorStatus = document.getAiDocId() == null || document.getAiDocId().isBlank()
                ? "PROCESSING" : "READY";
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
