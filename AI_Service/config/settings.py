from pydantic import model_validator
from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    # 智谱AI
    zhipuai_api_key: str = ""
    zhipuai_model: str = "glm-5"
    zhipuai_timeout: int = 600

    # Business Service 调用 AI Service 时使用的内部凭据
    internal_service_token: str = ""

    # Qdrant
    qdrant_host: str = "localhost"
    qdrant_port: int = 6333
    qdrant_api_key: str = ""
    qdrant_collection: str = "dehydrator_docs"

    # MinIO
    minio_endpoint: str = "localhost:9000"
    minio_access_key: str = ""
    minio_secret_key: str = ""
    minio_bucket: str = "dehydrator"

    # Redis
    redis_host: str = "localhost"
    redis_port: int = 6379
    redis_password: str = ""

    # RabbitMQ
    rabbitmq_host: str = "localhost"
    rabbitmq_port: int = 5672
    rabbitmq_user: str = ""
    rabbitmq_password: str = ""

    # 并发参数
    map_workers: int = 2

    # 分块参数
    chunk_size: int = 2000
    chunk_overlap: int = 300

    max_upload_bytes: int = 20 * 1024 * 1024
    max_request_bytes: int = 25 * 1024 * 1024
    max_text_chars: int = 10_000_000
    max_chunks_per_document: int = 10_000
    max_search_results: int = 20

    model_config = {"env_file": ".env", "env_file_encoding": "utf-8"}

    @model_validator(mode="after")
    def validate_runtime_configuration(self):
        required_secrets = {
            "ZHIPUAI_API_KEY": self.zhipuai_api_key,
            "INTERNAL_SERVICE_TOKEN": self.internal_service_token,
            "QDRANT_API_KEY": self.qdrant_api_key,
            "MINIO_ACCESS_KEY": self.minio_access_key,
            "MINIO_SECRET_KEY": self.minio_secret_key,
            "REDIS_PASSWORD": self.redis_password,
            "RABBITMQ_USER": self.rabbitmq_user,
            "RABBITMQ_PASSWORD": self.rabbitmq_password,
        }
        missing = [
            name for name, value in required_secrets.items()
            if not value.strip() or value.startswith("replace-with-")
        ]
        if missing:
            raise ValueError("缺少必需 Secret: " + ", ".join(missing))
        if not 1 <= self.map_workers <= 8:
            raise ValueError("MAP_WORKERS 必须在 1 到 8 之间")
        if not 500 <= self.chunk_size <= 8000:
            raise ValueError("CHUNK_SIZE 必须在 500 到 8000 之间")
        if not 0 <= self.chunk_overlap < self.chunk_size:
            raise ValueError("CHUNK_OVERLAP 必须满足 0 <= overlap < chunk_size")
        return self


settings = Settings()
