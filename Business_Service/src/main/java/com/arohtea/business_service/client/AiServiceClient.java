package com.arohtea.business_service.client;

import com.arohtea.business_service.model.AiModelConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Business Service 到 AI Service 的内部 HTTP 客户端。
 *
 * <p>Business Service 负责用户身份、数据库状态和资源生命周期，AI Service 负责
 * 文件解析、向量化以及分析模型调用。这个类是两者之间唯一的 HTTP 边界：它把
 * 数据库中的设置快照转成 AI Service 约定的 Header，并用独立的内部服务令牌证明
 * 调用来自可信后端。</p>
 *
 * <p>模型配置不会从前端请求直接传入，也不会从本地环境变量隐式回退；调用方必须
 * 把本次任务已经校验过的配置传进来。这样一个任务从开始到结束使用的模型和地址
 * 是确定的，同时避免在日志中暴露凭据。</p>
 */
@Component
@RequiredArgsConstructor
public class AiServiceClient {

    private final RestTemplate restTemplate;

    @Value("${ai-service.url}")
    private String aiServiceUrl;

    @Value("${ai-service.service-token}")
    private String serviceToken;

    /**
     * AI Service 返回的向量归档和分类建议。
     *
     * @param docId 新参考文档的 AI 文档 ID
     * @param folderName 模型推荐的文件夹名称
     * @param categoryName 模型推荐的分类名称
     * @param confidence 模型建议置信度
     */
    public record ArchiveReferenceResult(
            String docId,
            String folderName,
            String categoryName,
            Double confidence
    ) {}

    /**
     * 上传分析文档，并显式传递数据库中的向量与分块配置。
     *
     * <p>这是分析文档上传的便捷入口，默认把来源标记为
     * {@code analysis_document}。参考资料上传使用下面的重载方法，以便 AI Service
     * 区分两种文档并把向量放入不同的过滤范围。</p>
     *
     * @param fileBytes 文件内容
     * @param filename 文件名
     * @param vectorModel 向量模型完整配置
     * @param chunkSize 分块大小
     * @param chunkOverlap 分块重叠大小
     * @return AI Service 文档 ID
     */
    @SuppressWarnings("unchecked")
    public String uploadDocument(byte[] fileBytes, String filename,
                                 AiModelConfig vectorModel, Integer chunkSize, Integer chunkOverlap) {
        return uploadDocument(fileBytes, filename, vectorModel, chunkSize, chunkOverlap,
                "analysis_document", null);
    }

    /**
     * 上传指定来源的文档，并显式传递数据库配置。
     *
     * <p>文件内容放在 multipart 请求体中，模型、分块参数和来源信息放在 Header 中。
     * AI Service 返回的 {@code doc_id} 是后续分析、归档和删除向量时唯一需要保存的
     * 外部资源 ID；Business Service 会把它回写到对应的数据库记录。</p>
     *
     * @param fileBytes 文件内容
     * @param filename 文件名
     * @param vectorModel 向量模型完整配置
     * @param chunkSize 分块大小
     * @param chunkOverlap 分块重叠大小
     * @param sourceType 文档来源类型
     * @param libraryId 参考资料集 ID，分析文档可为空
     * @return AI Service 文档 ID
     */
    @SuppressWarnings("unchecked")
    public String uploadDocument(byte[] fileBytes, String filename,
                                 AiModelConfig vectorModel, Integer chunkSize, Integer chunkOverlap,
                                 String sourceType, String libraryId) {
        // Multipart 请求携带完整向量配置和来源信息，AI Service 不依赖自身的模型回退配置。
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(fileBytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        });

        // 请求体是文件，Header 是本次向量化使用的模型、分块和来源元数据。
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        // 服务令牌只证明调用来自 Business Service，不与用户的模型 API Key 混用。
        headers.set("X-Service-Token", serviceToken);
        // 向量模型配置由 Business Service 显式传入，AI Service 不再自行猜测默认模型。
        setModelHeaders(headers, "X-Embedding", vectorModel);
        // 只有调用方配置了分块参数时才发送 Header，保留服务端对缺省值的兼容行为。
        if (chunkSize != null) headers.set("X-Chunk-Size", chunkSize.toString());
        if (chunkOverlap != null) headers.set("X-Chunk-Overlap", chunkOverlap.toString());
        // 来源和资料库 ID 用于 AI Service 区分分析文档与参考资料的检索范围。
        if (sourceType != null && !sourceType.isBlank()) headers.set("X-Source-Type", sourceType);
        if (libraryId != null && !libraryId.isBlank()) headers.set("X-Library-Id", libraryId);

        // exchange 负责执行 HTTP 请求；响应体中的 doc_id 是后续回写和删除的外部主键。
        ResponseEntity<Map> resp = restTemplate.exchange(
                aiServiceUrl + "/api/document/upload",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class
        );
        return (String) resp.getBody().get("doc_id");
    }

    /**
     * 删除 AI Service 中指定文档的向量数据。
     *
     * <p>Business Service 不直接操作 Qdrant，因为它不知道向量点的内部结构；只把
     * AI 文档 ID 交给 AI Service，由对方按同一 ID 清理所有关联向量。</p>
     *
     * @param aiDocId AI Service 文档 ID
     */
    public void deleteDocument(String aiDocId) {
        // 删除接口只需要服务令牌和 AI 文档 ID；具体 point 范围由 AI Service 按 payload 清理。
        // 删除不需要重新上传文件或模型配置，只凭内部令牌和外部文档 ID 定位向量。
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Service-Token", serviceToken);
        // AI Service 负责删除该 ID 关联的全部向量点，Business Service 不直接访问 Qdrant。
        restTemplate.exchange(
                aiServiceUrl + "/api/document/" + aiDocId,
                HttpMethod.DELETE,
                new HttpEntity<>(headers),
                Void.class
        );
    }

    /**
     * 将分析文档归档到参考资料集，并使用数据库文本模型生成分类建议。
     *
     * <p>AI Service 在这里完成“复制向量语义”和“提出目录建议”，但不负责修改
     * Business Service 的文件夹/分类表。返回的名称和置信度会交给归档服务决定是否
     * 创建目录，因此模型建议不会绕过业务规则直接改变资料库结构。</p>
     *
     * @param docId AI Service 文档 ID
     * @param libraryId 目标参考资料集 ID
     * @param filename 文件名
     * @param folderCandidates 可选文件夹名称
     * @param categoryCandidates 可选分类名称
     * @param textModel 文本模型完整配置
     * @return 归档结果与分类置信度
     */
    @SuppressWarnings("unchecked")
    public ArchiveReferenceResult archiveReferenceDocument(String docId,
                                                          String libraryId,
                                                          String filename,
                                                          List<String> folderCandidates,
                                                          List<String> categoryCandidates,
                                                          AiModelConfig textModel) {
        // 候选目录只影响模型建议，真正的目录创建和置信度阈值由归档服务决定。
        // 归档请求使用文本模型，因为模型只负责理解文件名和候选目录并给出建议。
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Service-Token", serviceToken);
        setModelHeaders(headers, "X-Text", textModel);

        // 候选目录是只读输入；真实目录创建和置信度阈值由 ReferenceArchiveService 决定。
        Map<String, Object> body = Map.of(
                "libraryId", libraryId,
                "filename", filename,
                "folderCandidates", folderCandidates,
                "categoryCandidates", categoryCandidates
        );

        // AI Service 返回新的参考向量 ID，以及可选的目录建议和置信度。
        ResponseEntity<Map> resp = restTemplate.exchange(
                aiServiceUrl + "/api/document/" + docId + "/archive-reference",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class
        );
        Map<String, Object> result = resp.getBody();
        // 将外部 JSON 的字段转换成类型明确的 record，避免业务层到处处理原始 Map。
        return new ArchiveReferenceResult(
                result == null ? null : (String) result.get("doc_id"),
                result == null ? null : (String) result.get("folder_name"),
                result == null ? null : (String) result.get("category_name"),
                toDouble(result == null ? null : result.get("confidence"))
        );
    }

    /**
     * 把模型配置映射为 AI Service 约定的 Header 集合。
     *
     * @param headers 待写入的 HTTP Header
     * @param prefix `X-Text` 或 `X-Embedding`
     * @param config 模型配置，可为空
     */
    private void setModelHeaders(HttpHeaders headers, String prefix, AiModelConfig config) {
        // 可选模型配置为空时不写 Header，让调用方决定是否在更上层拒绝请求。
        if (config == null) {
            return;
        }
        // 每个模型的名称、地址和 Key 分开传递，便于使用 OpenAI 兼容的不同供应商。
        if (config.model() != null) headers.set(prefix + "-Model", config.model());
        if (config.url() != null) headers.set(prefix + "-Url", config.url());
        if (config.apiKey() != null) headers.set(prefix + "-Api-Key", config.apiKey());
    }

    /**
     * 将 HTTP JSON 中的数值转换为归档结果需要的 Double。
     *
     * @param value JSON 解析得到的候选值
     * @return 数值的 Double 表示；非数值时返回 null
     */
    private Double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return null;
    }
}
