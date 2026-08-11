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
 * <p>模型配置从数据库设置快照转换为 Header，服务令牌单独通过
 * `X-Service-Token` 传递；这里不读取前端输入的模型 Key，也不在日志中输出凭据。</p>
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

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set("X-Service-Token", serviceToken);
        setModelHeaders(headers, "X-Embedding", vectorModel);
        if (chunkSize != null) headers.set("X-Chunk-Size", chunkSize.toString());
        if (chunkOverlap != null) headers.set("X-Chunk-Overlap", chunkOverlap.toString());
        if (sourceType != null && !sourceType.isBlank()) headers.set("X-Source-Type", sourceType);
        if (libraryId != null && !libraryId.isBlank()) headers.set("X-Library-Id", libraryId);

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
     * @param aiDocId AI Service 文档 ID
     */
    public void deleteDocument(String aiDocId) {
        // 删除接口只需要服务令牌和 AI 文档 ID；具体 point 范围由 AI Service 按 payload 清理。
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Service-Token", serviceToken);
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
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Service-Token", serviceToken);
        setModelHeaders(headers, "X-Text", textModel);

        Map<String, Object> body = Map.of(
                "libraryId", libraryId,
                "filename", filename,
                "folderCandidates", folderCandidates,
                "categoryCandidates", categoryCandidates
        );

        ResponseEntity<Map> resp = restTemplate.exchange(
                aiServiceUrl + "/api/document/" + docId + "/archive-reference",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class
        );
        Map<String, Object> result = resp.getBody();
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
        if (config == null) {
            return;
        }
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
