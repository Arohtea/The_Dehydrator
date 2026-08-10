package com.arohtea.business_service.controller;

import com.arohtea.business_service.model.SystemSettings;
import com.arohtea.business_service.model.SystemSettingsUpdate;
import com.arohtea.business_service.service.SystemSettingsService;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final SystemSettingsService settingsService;

    /**
     * 返回当前数据库设置的脱敏视图。
     *
     * @return 模型配置状态、Tavily 状态和处理参数
     */
    @GetMapping
    public Map<String, Object> get() {
        return toResponse(settingsService.get());
    }

    /**
     * 校验并保存管理员提交的设置。
     *
     * @param input 设置更新内容
     * @return 保存后的脱敏设置；配置不完整时返回 422
     */
    @PutMapping
    public ResponseEntity<?> save(@Valid @RequestBody SystemSettingsUpdate input) {
        try {
            return ResponseEntity.ok(toResponse(settingsService.save(input)));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.unprocessableEntity()
                    .body(Map.of("error", exception.getMessage()));
        }
    }

    private Map<String, Object> toResponse(SystemSettings s) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("textModel", modelResponse(s.getTextModelName(), s.getTextModelUrl(), s.getTextModelApiKey()));
        r.put("vectorModel", modelResponse(s.getVectorModelName(), s.getVectorModelUrl(), s.getVectorModelApiKey()));
        String tavilyKey = s.getTavilyApiKey();
        r.put("tavilyApiKeyConfigured", tavilyKey != null && !tavilyKey.isBlank());
        r.put("tavilyApiKeyPreview", maskedPreview(tavilyKey));
        r.put("mapWorkers", s.getMapWorkers());
        r.put("chunkSize", s.getChunkSize());
        r.put("chunkOverlap", s.getChunkOverlap());
        return r;
    }

    private Map<String, Object> modelResponse(String model, String url, String apiKey) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("model", model);
        response.put("url", url);
        response.put("apiKeyConfigured", apiKey != null && !apiKey.isBlank());
        response.put("apiKeyPreview", maskedPreview(apiKey));
        return response;
    }

    private String maskedPreview(String apiKey) {
        return apiKey != null && !apiKey.isBlank() ? "***********" : null;
    }
}
