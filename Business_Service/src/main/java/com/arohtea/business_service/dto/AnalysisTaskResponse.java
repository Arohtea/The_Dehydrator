package com.arohtea.business_service.dto;

import com.arohtea.business_service.model.AnalysisTask;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;

/** 对外返回结构化分析结果，避免把数据库 JSON 文本交给前端自行猜测。 */
public record AnalysisTaskResponse(
        String id,
        String documentId,
        String mode,
        String status,
        List<String> referenceLibraryIds,
        List<String> referenceLibraryNames,
        JsonNode argumentChain,
        JsonNode logicFlaws,
        JsonNode crossValidation,
        Integer progress,
        String currentStep,
        LocalDateTime createdAt,
        LocalDateTime completedAt
) {

    /** 将任务中的 JSON 文本安全转换为对象或数组；无效结果返回 null，不泄露原始模型输出。 */
    public static AnalysisTaskResponse from(AnalysisTask task, ObjectMapper mapper) {
        return new AnalysisTaskResponse(
                task.getId(),
                task.getDocumentId(),
                task.getMode(),
                task.getStatus() == null ? null : task.getStatus().name(),
                parseStringList(task.getReferenceLibraryIds(), mapper),
                parseStringList(task.getReferenceLibraryNames(), mapper),
                parseJson(task.getArgumentChain(), mapper, true),
                parseJson(task.getLogicFlaws(), mapper, true),
                parseJson(task.getCrossValidation(), mapper, false),
                task.getProgress(),
                task.getCurrentStep(),
                task.getCreatedAt(),
                task.getCompletedAt()
        );
    }

    private static List<String> parseStringList(String value, ObjectMapper mapper) {
        if (value == null || value.isBlank()) return List.of();
        try {
            JsonNode node = mapper.readTree(value);
            if (!node.isArray()) return List.of();
            return mapper.convertValue(node, mapper.getTypeFactory()
                    .constructCollectionType(List.class, String.class));
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static JsonNode parseJson(String value, ObjectMapper mapper, boolean objectExpected) {
        if (value == null || value.isBlank()) return null;
        try {
            JsonNode node = mapper.readTree(value);
            if (node == null || node.isNull()) return null;
            if (node.isObject() && node.has("raw")) return null;
            if (objectExpected && !node.isObject()) return null;
            if (!objectExpected && !node.isArray()) return null;
            return node;
        } catch (Exception ignored) {
            return null;
        }
    }
}
