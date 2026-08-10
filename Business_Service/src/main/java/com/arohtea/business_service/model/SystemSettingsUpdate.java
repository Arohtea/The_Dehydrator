package com.arohtea.business_service.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;

public record SystemSettingsUpdate(
        @Valid ModelSettingsUpdate textModel,
        @Valid ModelSettingsUpdate vectorModel,
        @Size(max = 512) String tavilyApiKey,
        @Min(1) @Max(8) Integer mapWorkers,
        @Min(500) @Max(8000) Integer chunkSize,
        @Min(0) @Max(8000) Integer chunkOverlap) {

    /**
     * 设置页提交的单个模型配置。
     *
     * @param model 模型名称
     * @param url OpenAI 兼容接口根地址
     * @param apiKey API Key，留空表示保持原值
     */
    public record ModelSettingsUpdate(
            @Size(max = 100) String model,
            @Size(max = 2048) String url,
            @Size(max = 512) String apiKey) {
    }
}
