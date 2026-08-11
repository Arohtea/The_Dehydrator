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

/**
 * 管理员设置查询与保存接口，所有响应都对模型 Key 和 Tavily Key 做脱敏处理。
 *
 * <p>前端可以看到模型名称、地址和“是否已配置”，但永远拿不到真实 Secret；提交
 * 掩码值也不会覆盖数据库中的旧 Key，实际的部分更新和完整性校验由服务层负责。</p>
 */
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
            // 保存服务会合并非空字段并校验完整配置，响应再次转成脱敏视图。
            return ResponseEntity.ok(toResponse(settingsService.save(input)));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.unprocessableEntity()
                    .body(Map.of("error", exception.getMessage()));
        }
    }

    /**
     * 将数据库设置转换为前端可展示的脱敏结构。
     *
     * @param s 数据库设置
     * @return 不包含真实 Secret 的响应结构
     */
    private Map<String, Object> toResponse(SystemSettings s) {
        // 使用稳定字段结构，前端无需接触 SystemSettings 实体中的真实 Key。
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("textModel", modelResponse(s.getTextModelName(), s.getTextModelUrl(), s.getTextModelApiKey()));
        r.put("vectorModel", modelResponse(s.getVectorModelName(), s.getVectorModelUrl(), s.getVectorModelApiKey()));
        String tavilyKey = s.getTavilyApiKey();
        // 只返回配置状态和固定掩码，不根据 Key 长度或内容生成预览。
        r.put("tavilyApiKeyConfigured", tavilyKey != null && !tavilyKey.isBlank());
        r.put("tavilyApiKeyPreview", maskedPreview(tavilyKey));
        r.put("mapWorkers", s.getMapWorkers());
        r.put("chunkSize", s.getChunkSize());
        r.put("chunkOverlap", s.getChunkOverlap());
        return r;
    }

    /**
     * 组装单个模型的脱敏响应。
     *
     * @param model 模型名称
     * @param url 接口 URL
     * @param apiKey 原始 Key，仅用于判断配置状态和生成掩码
     * @return 模型名称、地址和 Key 状态
     */
    private Map<String, Object> modelResponse(String model, String url, String apiKey) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("model", model);
        response.put("url", url);
        response.put("apiKeyConfigured", apiKey != null && !apiKey.isBlank());
        response.put("apiKeyPreview", maskedPreview(apiKey));
        return response;
    }

    /**
     * 生成固定掩码，不根据 Key 长度泄露额外信息。
     *
     * @param apiKey 原始 API Key
     * @return 固定掩码；未配置时返回 null
     */
    private String maskedPreview(String apiKey) {
        return apiKey != null && !apiKey.isBlank() ? "***********" : null;
    }
}
