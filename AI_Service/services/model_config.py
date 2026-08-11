"""AI Service 接收的文本模型和向量模型配置校验。"""

from collections.abc import Mapping
from urllib.parse import urlparse

from pydantic import BaseModel, ConfigDict, Field, ValidationError, field_validator


class AIModelConfig(BaseModel):
    """一次 AI 调用使用的 OpenAI 兼容模型配置。

    `api_key` 使用 `apiKey` 作为外部 JSON/消息字段别名，以兼容 Business Service
    的驼峰命名协议；内部 Python 代码统一使用蛇形命名。
    """

    # 模型名由前端独立配置；不能依赖 AI Service 的隐藏默认模型。
    model: str = Field(min_length=1, max_length=100)
    # URL 和 API Key 与模型名一起构成一次调用的完整身份，三者不能跨请求混用。
    url: str = Field(min_length=1, max_length=2_048)
    api_key: str = Field(min_length=1, max_length=512, alias="apiKey")

    model_config = ConfigDict(populate_by_name=True)

    @field_validator("model", "url", "api_key", mode="before")
    @classmethod
    def strip_text(cls, value):
        """清理配置字符串两端空白，保留非字符串值交给 Pydantic 校验。"""
        # 只清理字符串，不提前强转其他类型，让 Pydantic 给出标准类型错误。
        return value.strip() if isinstance(value, str) else value

    @field_validator("url")
    @classmethod
    def validate_url(cls, value: str) -> str:
        """限制模型地址为 HTTP/HTTPS，并统一为带尾斜杠的基础 URL。"""
        # urlparse 先拆分协议和主机，再拒绝文件路径、相对路径等非服务地址。
        parsed = urlparse(value)
        if parsed.scheme not in {"http", "https"} or not parsed.netloc:
            raise ValueError("接口 URL 必须是有效的 HTTP/HTTPS 地址")
        # LangChain/OpenAI 兼容客户端按基础 URL 拼接路径，统一尾斜杠可避免重复或缺失分隔符。
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
        # 空 payload 转成空字典，让校验统一落到“缺少配置”的错误分支。
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
    # HTTP Header 使用短前缀区分文本模型和向量模型，具体字段映射保持与 RabbitMQ 配置一致。
    return parse_model_config(
        {
            "model": headers.get(f"{prefix}-Model"),
            "url": headers.get(f"{prefix}-Url"),
            "apiKey": headers.get(f"{prefix}-Api-Key"),
        },
        label,
    )
