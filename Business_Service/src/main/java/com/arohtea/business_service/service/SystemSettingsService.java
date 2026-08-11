package com.arohtea.business_service.service;

import com.arohtea.business_service.model.AiModelConfig;
import com.arohtea.business_service.model.SystemSettings;
import com.arohtea.business_service.model.SystemSettingsUpdate;
import com.arohtea.business_service.repository.SystemSettingsRepository;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * 管理数据库中的模型、联网和文档处理参数，并在使用前保证配置完整。
 */
@Service
public class SystemSettingsService {

    private final SystemSettingsRepository repo;

    /**
     * 创建设置服务。
     *
     * @param repo 设置仓库
     */
    public SystemSettingsService(SystemSettingsRepository repo) {
        this.repo = repo;
    }

    /**
     * 获取数据库中的当前设置。
     *
     * @return 当前设置；数据库未初始化时返回空设置对象
     */
    public SystemSettings get() {
        return repo.findById("default").orElse(new SystemSettings());
    }

    /**
     * 校验并保存可由管理员修改的设置字段。
     *
     * @param input 设置更新 DTO
     * @return 保存后的设置
     */
    public SystemSettings save(SystemSettingsUpdate input) {
        // 更新接口只覆盖非空字段，前端提交脱敏值时不会意外清空已保存的 Secret。
        SystemSettings existing = repo.findById("default").orElse(new SystemSettings());
        updateModelSettings(existing, input.textModel(), true);
        updateModelSettings(existing, input.vectorModel(), false);
        if (input.tavilyApiKey() != null && !input.tavilyApiKey().isBlank()) {
            if (input.tavilyApiKey().contains("***")) {
                throw new IllegalArgumentException("Tavily API Key 掩码值不能覆盖原 Key");
            }
            existing.setTavilyApiKey(input.tavilyApiKey().trim());
        }
        if (input.mapWorkers() != null) existing.setMapWorkers(input.mapWorkers());
        if (input.chunkSize() != null) existing.setChunkSize(input.chunkSize());
        if (input.chunkOverlap() != null) existing.setChunkOverlap(input.chunkOverlap());

        validateProcessingConfig(existing);
        validateOptionalModelConfig(existing.getTextModelConfig(), "文本模型");
        validateOptionalModelConfig(existing.getVectorModelConfig(), "向量模型");
        return repo.save(existing);
    }

    /**
     * 获取并校验可用于文本调用的配置。
     *
     * @param settings 本次任务使用的设置快照
     * @return 文本模型配置
     */
    public AiModelConfig requireTextModelConfig(SystemSettings settings) {
        validateModelConfig(settings.getTextModelConfig(), "文本模型");
        return settings.getTextModelConfig();
    }

    /**
     * 获取并校验可用于向量调用的配置。
     *
     * @param settings 本次任务使用的设置快照
     * @return 向量模型配置
     */
    public AiModelConfig requireVectorModelConfig(SystemSettings settings) {
        validateModelConfig(settings.getVectorModelConfig(), "向量模型");
        return settings.getVectorModelConfig();
    }

    /**
     * 将单个模型更新 DTO 的非空字段合并到现有设置。
     *
     * @param existing 当前数据库设置
     * @param input 前端提交的模型更新
     * @param textModel true 表示文本模型，false 表示向量模型
     * @throws IllegalArgumentException 脱敏 Key 被误当成新值提交
     */
    private void updateModelSettings(SystemSettings existing,
                                     SystemSettingsUpdate.ModelSettingsUpdate input,
                                     boolean textModel) {
        if (input == null) {
            return;
        }
        if (input.model() != null && !input.model().isBlank()) {
            if (textModel) existing.setTextModelName(input.model().trim());
            else existing.setVectorModelName(input.model().trim());
        }
        if (input.url() != null && !input.url().isBlank()) {
            if (textModel) existing.setTextModelUrl(input.url().trim());
            else existing.setVectorModelUrl(input.url().trim());
        }
        if (input.apiKey() != null && !input.apiKey().isBlank()) {
            if (input.apiKey().contains("***")) {
                throw new IllegalArgumentException((textModel ? "文本" : "向量")
                        + "模型 API Key 掩码值不能覆盖原 Key");
            }
            if (textModel) existing.setTextModelApiKey(input.apiKey().trim());
            else existing.setVectorModelApiKey(input.apiKey().trim());
        }
    }

    /**
     * 校验切分和并发参数的完整性及基本关系。
     *
     * @param settings 待保存设置
     * @throws IllegalArgumentException 参数缺失或重叠长度不合法
     */
    private void validateProcessingConfig(SystemSettings settings) {
        if (settings.getMapWorkers() == null
                || settings.getChunkSize() == null
                || settings.getChunkOverlap() == null) {
            throw new IllegalArgumentException("处理参数 mapWorkers、chunkSize 和 chunkOverlap 必须完整配置");
        }
        if (settings.getChunkOverlap() >= settings.getChunkSize()) {
            throw new IllegalArgumentException("chunkOverlap 必须小于 chunkSize");
        }
    }

    /**
     * 允许模型三项同时为空，但禁止只填写其中一部分。
     *
     * @param config 待校验模型配置
     * @param label 用户可读的模型名称
     */
    private void validateOptionalModelConfig(AiModelConfig config, String label) {
        if (isBlank(config.model()) && isBlank(config.url()) && isBlank(config.apiKey())) {
            return;
        }
        validateModelConfig(config, label);
    }

    /**
     * 校验模型名称、接口地址、Key 长度和 HTTP/HTTPS 地址格式。
     *
     * @param config 待校验模型配置
     * @param label 用户可读的模型名称
     * @throws IllegalArgumentException 配置不完整或格式错误
     */
    private void validateModelConfig(AiModelConfig config, String label) {
        if (config == null || isBlank(config.model())) {
            throw new IllegalArgumentException(label + "名称不能为空");
        }
        if (config.model().trim().length() > 100) {
            throw new IllegalArgumentException(label + "名称不能超过 100 个字符");
        }
        if (isBlank(config.url())) {
            throw new IllegalArgumentException(label + "接口 URL 不能为空");
        }
        if (config.url().trim().length() > 2048) {
            throw new IllegalArgumentException(label + "接口 URL 不能超过 2048 个字符");
        }
        if (isBlank(config.apiKey())) {
            throw new IllegalArgumentException(label + " API Key 不能为空");
        }
        if (config.apiKey().length() > 512) {
            throw new IllegalArgumentException(label + " API Key 不能超过 512 个字符");
        }
        try {
            URI uri = new URI(config.url().trim());
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null) {
                throw new IllegalArgumentException(label + "接口 URL 必须是有效的 HTTP/HTTPS 地址");
            }
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException(label + "接口 URL 无效");
        }
    }

    /**
     * 判断字符串是否为空或只包含空白。
     *
     * @param value 待判断字符串
     * @return 为空或空白时返回 true
     */
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
