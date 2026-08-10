from langchain_openai import ChatOpenAI

from config.settings import settings
from services.model_config import AIModelConfig


def get_chat_model(config: AIModelConfig, streaming: bool = True, **kwargs) -> ChatOpenAI:
    """按显式文本模型配置创建聊天模型客户端。

    Args:
        config: 文本模型名称、OpenAI 兼容地址和 API Key。
        streaming: 是否启用流式响应。
        **kwargs: 传给 ``ChatOpenAI`` 的其他调用参数。

    Returns:
        已绑定本次请求配置的聊天模型客户端。

    Raises:
        ValueError: 未提供文本模型配置。
    """
    if config is None:
        raise ValueError("文本模型配置不能为空")
    return ChatOpenAI(
        model=config.model,
        base_url=config.url,
        api_key=config.api_key,
        temperature=0.1,
        streaming=streaming,
        timeout=settings.ai_request_timeout_seconds,
        **kwargs,
    )
