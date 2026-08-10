from pathlib import Path

from pydantic import Field, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


ENV_FILE = Path(__file__).resolve().parents[2] / "docker" / ".env"


class Settings(BaseSettings):
    ai_service_host: str
    ai_service_port: int = Field(ge=1, le=65535)
    ai_request_timeout_seconds: int = Field(gt=0)
    ai_tavily_timeout_seconds: int = Field(gt=0)
    ai_rabbitmq_heartbeat_seconds: int = Field(gt=0)
    ai_analysis_branch_workers: int = Field(ge=1, le=8)

    internal_service_token: str

    qdrant_host: str
    qdrant_http_port: int = Field(ge=1, le=65535)
    qdrant_api_key: str
    qdrant_collection: str

    redis_host: str
    redis_port: int = Field(ge=1, le=65535)
    redis_password: str
    redis_stream_prefix: str
    redis_cancel_prefix: str

    rabbitmq_host: str
    rabbitmq_port: int = Field(ge=1, le=65535)
    rabbitmq_user: str
    rabbitmq_password: str
    rabbitmq_analysis_exchange: str
    rabbitmq_request_queue: str
    rabbitmq_result_queue: str
    rabbitmq_progress_queue: str

    ai_max_upload_bytes: int = Field(gt=0)
    ai_max_request_bytes: int = Field(gt=0)
    ai_max_text_chars: int = Field(gt=0)
    ai_max_chunks_per_document: int = Field(gt=0)
    ai_max_search_results: int = Field(gt=0)
    ai_qdrant_scroll_page_size: int = Field(gt=0, le=10_000)
    ai_cancel_check_interval_tokens: int = Field(gt=0)
    ai_upload_rate_limit: str
    ai_delete_rate_limit: str
    ai_archive_rate_limit: str

    model_config = SettingsConfigDict(
        env_file=ENV_FILE,
        env_file_encoding="utf-8",
        extra="ignore",
    )

    @model_validator(mode="after")
    def validate_runtime_configuration(self):
        """拒绝空值、占位符和不一致的容量配置。"""
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
        missing = [
            name for name, value in required_values.items()
            if not value.strip() or value.lower().startswith(("replace-with-", "change-me"))
        ]
        if missing:
            raise ValueError("缺少必需配置: " + ", ".join(missing))
        if self.ai_max_request_bytes < self.ai_max_upload_bytes:
            raise ValueError("AI_MAX_REQUEST_BYTES 不能小于 AI_MAX_UPLOAD_BYTES")
        return self


settings = Settings()
