package com.arohtea.business_service.client;

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

    @Value("${ai-service.service-token:}")
    private String serviceToken;

    public record ArchiveReferenceResult(
            String docId,
            String folderName,
            String categoryName,
            Double confidence
    ) {}

    @SuppressWarnings("unchecked")
    public String uploadDocument(byte[] fileBytes, String filename,
                                 String apiKey, Integer chunkSize, Integer chunkOverlap) {
        return uploadDocument(
                fileBytes,
                filename,
                apiKey,
                chunkSize,
                chunkOverlap,
                "analysis_document",
                null
        );
    }

    @SuppressWarnings("unchecked")
    public String uploadDocument(byte[] fileBytes, String filename,
                                 String apiKey, Integer chunkSize, Integer chunkOverlap,
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
        if (apiKey != null) headers.set("X-Api-Key", apiKey);
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

    @SuppressWarnings("unchecked")
    public ArchiveReferenceResult archiveReferenceDocument(String docId,
                                                          String libraryId,
                                                          String filename,
                                                          List<String> folderCandidates,
                                                          List<String> categoryCandidates,
                                                          String apiKey,
                                                          String model) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Service-Token", serviceToken);
        if (apiKey != null) headers.set("X-Api-Key", apiKey);
        if (model != null) headers.set("X-Model", model);

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

    private Double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return null;
    }
}
