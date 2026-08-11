"""OpenAI 兼容向量模型客户端及向量生成封装。"""

from langchain_openai import OpenAIEmbeddings

from config.settings import settings
from services.model_config import AIModelConfig

# 同一配置复用客户端可以减少连接初始化；Key 包含三项，避免不同模型或 Key 串用。
_embeddings: dict[tuple[str, str, str], OpenAIEmbeddings] = {}


def get_embeddings(config: AIModelConfig) -> OpenAIEmbeddings:
    """获取与显式配置对应的向量客户端。

    Args:
        config: 向量模型名称、OpenAI 兼容地址和 API Key。

    Returns:
        仅与该配置共享的向量客户端。

    Raises:
        ValueError: 未提供向量模型配置。

    Notes:
        客户端按模型、接口地址和 Key 组成的完整配置缓存；配置变化会得到新的
        客户端，避免不同设置之间共享错误的模型连接。
    """
    if config is None:
        raise ValueError("向量模型配置不能为空")
    # 模型名、服务地址和凭证共同决定向量语义，任何一项变化都必须创建新客户端。
    cache_key = (config.model, config.url, config.api_key)
    if cache_key not in _embeddings:
        # 使用请求级配置创建 OpenAI 兼容客户端，不读取其他环境中的隐式模型设置。
        _embeddings[cache_key] = OpenAIEmbeddings(
            model=config.model,
            base_url=config.url,
            api_key=config.api_key,
            timeout=settings.ai_request_timeout_seconds,
        )
    return _embeddings[cache_key]


def embed_texts(texts: list[str], config: AIModelConfig) -> list[list[float]]:
    """使用指定向量模型生成文档向量。

    Args:
        texts: 待向量化的文档片段。
        config: 本次调用使用的向量模型配置。

    Returns:
        与输入顺序一致的向量列表。
    """
    # LangChain 保持输入顺序返回向量，调用方可以按同一索引把向量和文本片段写入 Qdrant。
    return get_embeddings(config).embed_documents(texts)


def embed_query(text: str, config: AIModelConfig) -> list[float]:
    """使用指定向量模型生成检索向量。

    Args:
        text: 待检索文本。
        config: 本次调用使用的向量模型配置。

    Returns:
        用于相似度检索的向量。
    """
    # 查询向量必须由与资料入库兼容的配置生成，否则维度或语义不一致会导致检索失败。
    return get_embeddings(config).embed_query(text)
