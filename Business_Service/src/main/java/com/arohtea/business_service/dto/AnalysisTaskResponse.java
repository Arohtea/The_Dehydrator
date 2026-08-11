package com.arohtea.business_service.dto;

import com.arohtea.business_service.model.AnalysisTask;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 对外返回结构化分析结果，避免把数据库 JSON 文本交给前端自行猜测。
 *
 * @param id 任务 ID
 * @param documentId 业务文档 ID
 * @param mode 分析模式
 * @param status 任务状态名称
 * @param referenceLibraryIds 参考资料库 ID 列表
 * @param referenceLibraryNames 创建任务时保存的资料库名称快照
 * @param argumentChain 结构化论据链
 * @param logicFlaws 结构化逻辑漏洞结果
 * @param crossValidation 结构化交叉验证结果
 * @param progress 当前进度百分比
 * @param currentStep 面向用户的当前步骤
 * @param createdAt 创建时间
 * @param completedAt 完成时间
 */
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

    /**
     * 将任务中的 JSON 文本安全转换为对象或数组。
     *
     * @param task 任务实体
     * @param mapper Jackson JSON 转换器
     * @return 脱离数据库 JSON 文本的对外响应
     */
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

    /**
     * 解析数据库中的 JSON 字符串数组。
     *
     * @param value JSON 文本
     * @param mapper Jackson JSON 转换器
     * @return 字符串列表；空值、非数组或非法 JSON 返回空列表
     */
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

    /**
     * 解析并限制结果 JSON 的顶层类型。
     *
     * @param value JSON 文本
     * @param mapper Jackson JSON 转换器
     * @param objectExpected true 表示要求对象，false 表示要求数组
     * @return 合法 JSON 节点；非法、类型不符或疑似原始 raw 输出时返回 null
     */
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
