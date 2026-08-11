"""AI Service 的运行时配置模型。

配置统一从仓库根目录下的 `docker/.env` 读取，并在应用导入阶段完成基础值、
占位符和容量关系校验；这样服务不会带着不完整的基础设施配置启动。
"""

from pathlib import Path

from pydantic import Field, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


# AI Service 与基础设施共用仓库根目录下的 docker/.env，避免从当前启动目录误读配置。
ENV_FILE = Path(__file__).resolve().parents[2] / "docker" / ".env"


class Settings(BaseSettings):
    """AI Service 使用的基础设施、容量和限流配置。

    字段名称与 `docker/.env` 的大写环境变量通过 Pydantic Settings 自动映射；
    模型、向量和 Tavily 的业务配置不在这里保存，而是由 Business Service 在
    每次请求或消息中显式传入。
    """

    # HTTP 服务监听配置，端口范围由 Field 在启动时直接校验。
    ai_service_host: str
    ai_service_port: int = Field(ge=1, le=65535)
    ai_request_timeout_seconds: int = Field(gt=0)
    ai_tavily_timeout_seconds: int = Field(gt=0)
    ai_rabbitmq_heartbeat_seconds: int = Field(gt=0)
    ai_analysis_branch_workers: int = Field(ge=1, le=8)

    # Business Service 调用内部接口时必须携带的共享令牌。
    internal_service_token: str

    # Qdrant 保存文档片段和嵌入向量，是 AI Service 的主要持久化依赖。
    qdrant_host: str
    qdrant_http_port: int = Field(ge=1, le=65535)
    qdrant_api_key: str
    qdrant_collection: str

    # Redis 保存流式输出和取消标记；两个前缀用于隔离不同用途的 Key/Stream。
    redis_host: str
    redis_port: int = Field(ge=1, le=65535)
    redis_password: str
    redis_stream_prefix: str
    redis_cancel_prefix: str

    # RabbitMQ 负责接收分析任务并发布结果、进度和取消相关消息。
    rabbitmq_host: str
    rabbitmq_port: int = Field(ge=1, le=65535)
    rabbitmq_user: str
    rabbitmq_password: str
    rabbitmq_analysis_exchange: str
    rabbitmq_request_queue: str
    rabbitmq_result_queue: str
    rabbitmq_progress_queue: str

    # 下面的容量与限流配置保护解析、嵌入、联网搜索和消息输出不被单个请求耗尽。
    ai_max_upload_bytes: int = Field(gt=0)
    ai_max_request_bytes: int = Field(gt=0)
    ai_max_text_chars: int = Field(gt=0)
    ai_max_chunks_per_document: int = Field(gt=0)
    ai_max_search_results: int = Field(gt=0)
    ai_qdrant_scroll_page_size: int = Field(gt=0, le=10_000)
    ai_cancel_check_interval_tokens: int = Field(gt=0)
    ai_stream_batch_chars: int = Field(default=256, gt=0)
    ai_stream_batch_ms: int = Field(default=100, gt=0)
    ai_redis_stream_max_length: int = Field(default=10_000, gt=0)
    ai_redis_stream_ttl_seconds: int = Field(default=86_400, gt=0)
    ai_upload_rate_limit: str
    ai_delete_rate_limit: str
    ai_archive_rate_limit: str

    # 只从指定 env 文件和环境变量读取配置；未知变量忽略，便于与其他服务共用 env 文件。
    model_config = SettingsConfigDict(
        env_file=ENV_FILE,
        env_file_encoding="utf-8",
        extra="ignore",
    )

    @model_validator(mode="after")
    def validate_runtime_configuration(self):
        """拒绝空值、占位符和不一致的容量配置。

        Returns:
            完成校验后的当前配置对象。

        Raises:
            ValueError: 必需配置为空、仍是示例占位符，或请求体容量小于单文件
                上传容量时抛出。
        """
        # 这些值没有合理的程序默认值，缺失时应在进程启动阶段失败，而不是运行到请求中才失败。
        required_values = {
            "AI_SERVICE_HOST": self.ai_service_host,
            "INTERNAL_SERVICE_TOKEN": self.internal_service_token,
            "QDRANT_HOST": self.qdrant_host,
            "QDRANT_API_KEY": self.qdrant_api_key,
            "QDRANT_COLLECTION": self.qdrant_collection,
            "REDIS_HOST": self.redis_host,
            "REDIS_PASSWORD": self.redis_password,
            "REDIS_STREAM_PREFIX": self.redis_stream_prefix,
            "REDIS_CANCEL_PREFIX": self.redis_cancel_prefix,
            "RABBITMQ_HOST": self.rabbitmq_host,
            "RABBITMQ_USER": self.rabbitmq_user,
            "RABBITMQ_PASSWORD": self.rabbitmq_password,
            "RABBITMQ_ANALYSIS_EXCHANGE": self.rabbitmq_analysis_exchange,
            "RABBITMQ_REQUEST_QUEUE": self.rabbitmq_request_queue,
            "RABBITMQ_RESULT_QUEUE": self.rabbitmq_result_queue,
            "RABBITMQ_PROGRESS_QUEUE": self.rabbitmq_progress_queue,
            "AI_UPLOAD_RATE_LIMIT": self.ai_upload_rate_limit,
            "AI_DELETE_RATE_LIMIT": self.ai_delete_rate_limit,
            "AI_ARCHIVE_RATE_LIMIT": self.ai_archive_rate_limit,
        }
        # 示例文件里的占位符也视为未配置，防止服务看似启动成功但所有外部调用都失败。
        missing = [
            name for name, value in required_values.items()
            if not value.strip() or value.lower().startswith(("replace-with-", "change-me"))
        ]
        if missing:
            raise ValueError("缺少必需配置: " + ", ".join(missing))
        # 一个 HTTP 请求可能携带一个文件和额外协议开销，因此请求体上限不能小于文件上限。
        if self.ai_max_request_bytes < self.ai_max_upload_bytes:
            raise ValueError("AI_MAX_REQUEST_BYTES 不能小于 AI_MAX_UPLOAD_BYTES")
        return self


settings = Settings()
