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

@Component
@RequiredArgsConstructor
public class AiServiceClient {

    private final RestTemplate restTemplate;

    @Value("${ai-service.url}")
    private String aiServiceUrl;

    @Value("${ai-service.service-token}")
    private String serviceToken;

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

    private void setModelHeaders(HttpHeaders headers, String prefix, AiModelConfig config) {
        if (config == null) {
            return;
        }
        if (config.model() != null) headers.set(prefix + "-Model", config.model());
        if (config.url() != null) headers.set(prefix + "-Url", config.url());
        if (config.apiKey() != null) headers.set(prefix + "-Api-Key", config.apiKey());
    }

    private Double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return null;
    }
}
