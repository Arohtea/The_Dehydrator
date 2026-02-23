from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    # 智谱AI
    zhipuai_api_key: str = ""
    zhipuai_model: str = "glm-4.6"
    zhipuai_timeout: int =600

    # Qdrant
    qdrant_host: str = "192.168.1.4"
    qdrant_port: int = 6333
    qdrant_collection: str = "dehydrator_docs"

    # MinIO
    minio_endpoint: str = "192.168.1.4:9000"
    minio_access_key: str = "admin"
    minio_secret_key: str = "12345678"
    minio_bucket: str = "dehydrator"

    # Redis
    redis_host: str = "localhost"
    redis_port: int = 6379

    # RabbitMQ
    rabbitmq_host: str = "192.168.1.4"
    rabbitmq_port: int = 5672
    rabbitmq_user: str = "admin"
    rabbitmq_password: str = "admin"

    # 并发参数
    map_workers: int = 2

    # 分块参数
    chunk_size: int = 2000
    chunk_overlap: int = 300

    model_config = {"env_file": ".env", "env_file_encoding": "utf-8"}


settings = Settings()
