package com.arohtea.business_service.model;

/**
 * AI 模型调用所需的完整配置。
 *
 * <p>这是一次请求使用的不可变配置快照，不负责从数据库读取或修改设置。服务层
 * 先完成校验，再把该快照传给 HTTP 客户端和 RabbitMQ 消息，保证一次任务不会混用
 * 不同时间读取到的模型参数。</p>
 *
 * @param model 模型名称
 * @param url OpenAI 兼容接口根地址
 * @param apiKey 模型 API Key
 */
public record AiModelConfig(String model, String url, String apiKey) {
}
