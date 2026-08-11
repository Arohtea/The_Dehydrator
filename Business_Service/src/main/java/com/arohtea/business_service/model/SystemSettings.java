package com.arohtea.business_service.model;

import jakarta.persistence.*;
import lombok.Data;

/**
 * 系统设置单例实体。
 *
 * <p>固定主键 `default` 确保只有一行；API Key 仅在服务内部使用，控制器响应会
 * 返回配置状态而不会返回真实值。</p>
 */
@Data
@Entity
@Table(name = "system_settings")
public class SystemSettings {

    @Id
    private String id = "default";

    @Column(length = 100)
    private String textModelName;
    @Column(length = 2048)
    private String textModelUrl;
    @Column(length = 512)
    private String textModelApiKey;
    @Column(length = 100)
    private String vectorModelName;
    @Column(length = 2048)
    private String vectorModelUrl;
    @Column(length = 512)
    private String vectorModelApiKey;

    @Column(length = 512)
    private String tavilyApiKey;
    private Integer mapWorkers;
    private Integer chunkSize;
    private Integer chunkOverlap;

    /**
     * 获取文本模型配置。
     *
     * @return 文本模型配置
     */
    public AiModelConfig getTextModelConfig() {
        return new AiModelConfig(textModelName, textModelUrl, textModelApiKey);
    }

    /**
     * 获取向量模型配置。
     *
     * @return 向量模型配置
     */
    public AiModelConfig getVectorModelConfig() {
        return new AiModelConfig(vectorModelName, vectorModelUrl, vectorModelApiKey);
    }
}
