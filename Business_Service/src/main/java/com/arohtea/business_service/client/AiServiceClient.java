package com.arohtea.business_service.client;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class AiServiceClient {

    private final RestTemplate restTemplate;

    @Value("${ai-service.url}")
    private String aiServiceUrl;

    @SuppressWarnings("unchecked")
    public String uploadDocument(byte[] fileBytes, String filename,
                                 String apiKey, Integer chunkSize, Integer chunkOverlap) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(fileBytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        });

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        if (apiKey != null) headers.set("X-Api-Key", apiKey);
        if (chunkSize != null) headers.set("X-Chunk-Size", chunkSize.toString());
        if (chunkOverlap != null) headers.set("X-Chunk-Overlap", chunkOverlap.toString());

        ResponseEntity<Map> resp = restTemplate.exchange(
                aiServiceUrl + "/api/document/upload",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class
        );
        return (String) resp.getBody().get("doc_id");
    }

    public void deleteDocument(String aiDocId) {
        restTemplate.delete(aiServiceUrl + "/api/document/" + aiDocId);
    }
}
