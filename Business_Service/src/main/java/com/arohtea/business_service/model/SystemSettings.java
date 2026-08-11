package com.arohtea.business_service.model;

import jakarta.persistence.*;
import lombok.Data;

/**
 * 系统设置单例实体。
 *
 * <p>固定主键 {@code default} 确保只有一行；文本模型负责分析和分类建议，向量模型
 * 负责文档切分与检索索引，两者可以使用不同接口。API Key 仅在服务内部使用，控制器
 * 响应会返回配置状态而不会返回真实值。</p>
 */
@Data
@Entity
@Table(name = "system_settings")
public class SystemSettings {

    /** 单例记录主键，始终使用 default。 */
    @Id
    private String id = "default";

    /** 文本模型名称，用于论证分析和资料归档分类。 */
    @Column(length = 100)
    private String textModelName;
    /** 文本模型 OpenAI 兼容接口地址。 */
    @Column(length = 2048)
    private String textModelUrl;
    /** 文本模型 API Key，只允许后端内部读取。 */
    @Column(length = 512)
    private String textModelApiKey;
    /** 向量模型名称，用于上传后的文档向量化。 */
    @Column(length = 100)
    private String vectorModelName;
    /** 向量模型 OpenAI 兼容接口地址。 */
    @Column(length = 2048)
    private String vectorModelUrl;
    /** 向量模型 API Key，只允许后端内部读取。 */
    @Column(length = 512)
    private String vectorModelApiKey;

    /** 深度分析可选的联网搜索凭据，快速分析不会使用。 */
    @Column(length = 512)
    private String tavilyApiKey;
    /** AI Service 进行 Map 阶段时使用的并发工作数。 */
    private Integer mapWorkers;
    /** 文档切分时每个文本块的目标长度。 */
    private Integer chunkSize;
    /** 相邻文本块共享的字符长度，用于保留跨块上下文。 */
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
