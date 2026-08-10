package com.arohtea.business_service.service;

import com.arohtea.business_service.model.SystemSettings;
import com.arohtea.business_service.model.SystemSettingsUpdate;
import com.arohtea.business_service.repository.SystemSettingsRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class SystemSettingsService {

    private final SystemSettingsRepository repo;

    private final Set<String> allowedModels;
    private final String defaultModel;

    /**
     * 创建设置服务并解析模型白名单。
     *
     * @param repo 设置仓库
     * @param allowedModels 逗号分隔的模型白名单
     */
    public SystemSettingsService(
            SystemSettingsRepository repo,
            @Value("${security.allowed-models:glm-5,glm-4.6}") String allowedModels) {
        this.repo = repo;
        this.allowedModels = Arrays.stream(allowedModels.split(","))
                .map(String::trim)
                .filter(model -> !model.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        if (this.allowedModels.isEmpty()) {
            throw new IllegalStateException("模型白名单不能为空");
        }
        this.defaultModel = this.allowedModels.contains("glm-5")
                ? "glm-5"
                : this.allowedModels.iterator().next();
    }

    /**
     * 获取经过安全边界归一化的当前设置。
     *
     * @return 不包含越界运行参数的设置
     */
    public SystemSettings get() {
        SystemSettings settings = repo.findById("default").orElse(new SystemSettings());
        if (!allowedModels.contains(settings.getModel())) {
            settings.setModel(defaultModel);
        }
        if (settings.getMapWorkers() == null || settings.getMapWorkers() < 1 || settings.getMapWorkers() > 8) {
            settings.setMapWorkers(2);
        }
        if (settings.getChunkSize() == null || settings.getChunkSize() < 500 || settings.getChunkSize() > 8000) {
            settings.setChunkSize(2000);
        }
        if (settings.getChunkOverlap() == null
                || settings.getChunkOverlap() < 0
                || settings.getChunkOverlap() >= settings.getChunkSize()) {
            settings.setChunkOverlap(300);
        }
        return settings;
    }

    /**
     * 校验并保存可由管理员修改的设置字段。
     *
     * @param input 设置更新 DTO
     * @return 保存后的设置
     */
    public SystemSettings save(SystemSettingsUpdate input) {
        SystemSettings existing = get();
        if (input.apiKey() != null && !input.apiKey().isBlank()) {
            if (input.apiKey().contains("***")) {
                throw new IllegalArgumentException("API Key 掩码值不能覆盖原 Key");
            }
            existing.setApiKey(input.apiKey().trim());
        }
        if (input.model() != null && !input.model().isBlank()) {
            String model = input.model().trim();
            if (!allowedModels.contains(model)) {
                throw new IllegalArgumentException("model 不在允许列表中");
            }
            existing.setModel(model);
        }
        if (input.mapWorkers() != null) existing.setMapWorkers(input.mapWorkers());
        if (input.chunkSize() != null) existing.setChunkSize(input.chunkSize());
        if (input.chunkOverlap() != null) existing.setChunkOverlap(input.chunkOverlap());

        int chunkSize = existing.getChunkSize() == null ? 2000 : existing.getChunkSize();
        int chunkOverlap = existing.getChunkOverlap() == null ? 300 : existing.getChunkOverlap();
        if (chunkOverlap >= chunkSize) {
            throw new IllegalArgumentException("chunkOverlap 必须小于 chunkSize");
        }
        return repo.save(existing);
    }
}
