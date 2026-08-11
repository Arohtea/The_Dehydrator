package com.arohtea.business_service.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;

/**
 * 设置页提交的可选更新字段。
 *
 * <p>空字段表示保持现有值；模型三项由服务层进一步判断是全部为空还是完整配置。</p>
 */
public record SystemSettingsUpdate(
        @Valid ModelSettingsUpdate textModel,
        @Valid ModelSettingsUpdate vectorModel,
        @Size(max = 512) String tavilyApiKey,
        @Min(1) @Max(8) Integer mapWorkers,
        @Min(500) @Max(8000) Integer chunkSize,
        @Min(0) @Max(8000) Integer chunkOverlap) {

    /**
     * 单个文本模型或向量模型的更新字段。
     *
     * <p>该对象中的空字段不是“清空”，而是“保留数据库原值”；完整性和掩码 Key
     * 检查由 SystemSettingsService 执行。</p>
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
