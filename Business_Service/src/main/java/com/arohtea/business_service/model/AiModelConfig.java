package com.arohtea.business_service.model;

/**
 * AI 模型调用所需的完整配置。
 *
 * @param model 模型名称
 * @param url OpenAI 兼容接口根地址
 * @param apiKey 模型 API Key
 */
public record AiModelConfig(String model, String url, String apiKey) {
}
