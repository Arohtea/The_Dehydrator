from collections.abc import Mapping
from urllib.parse import urlparse

from pydantic import BaseModel, ConfigDict, Field, ValidationError, field_validator


class AIModelConfig(BaseModel):
    """一次 AI 调用使用的 OpenAI 兼容模型配置。"""

    model: str = Field(min_length=1, max_length=100)
    url: str = Field(min_length=1, max_length=2_048)
    api_key: str = Field(min_length=1, max_length=512, alias="apiKey")

    model_config = ConfigDict(populate_by_name=True)

    @field_validator("model", "url", "api_key", mode="before")
    @classmethod
    def strip_text(cls, value):
        return value.strip() if isinstance(value, str) else value

    @field_validator("url")
    @classmethod
    def validate_url(cls, value: str) -> str:
        parsed = urlparse(value)
        if parsed.scheme not in {"http", "https"} or not parsed.netloc:
            raise ValueError("接口 URL 必须是有效的 HTTP/HTTPS 地址")
        return value.rstrip("/") + "/"


def parse_model_config(payload: Mapping | None, label: str) -> AIModelConfig:
    """从 RabbitMQ 或 HTTP 请求数据中解析模型配置。

    Args:
        payload: 外部传入的配置对象。
        label: 错误消息中的配置名称。

    Returns:
        已归一化并校验的模型配置。

    Raises:
        ValueError: 配置缺失或格式不合法。
    """
    try:
        return AIModelConfig.model_validate(payload or {})
    except ValidationError as error:
        raise ValueError(f"{label}配置无效，请检查模型名称、接口 URL 和 API Key") from error


def parse_header_model_config(headers, prefix: str, label: str) -> AIModelConfig:
    """从 Business Service 传入的 HTTP Header 解析模型配置。

    Args:
        headers: 支持按名称读取值的 HTTP Header 集合。
        prefix: Header 前缀，例如 ``X-Text`` 或 ``X-Embedding``。
        label: 错误消息中的配置名称。

    Returns:
        已归一化并校验的模型配置。

    Raises:
        ValueError: Header 缺失或配置格式不合法。
    """
    return parse_model_config(
        {
            "model": headers.get(f"{prefix}-Model"),
            "url": headers.get(f"{prefix}-Url"),
            "apiKey": headers.get(f"{prefix}-Api-Key"),
        },
        label,
    )
