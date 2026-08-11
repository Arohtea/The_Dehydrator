"""AI Service 的 FastAPI 入口与应用生命周期管理。

本模块只负责组装 HTTP 路由、限流中间件和 RabbitMQ 消费者；具体的文档处理、
向量检索与分析逻辑由 `services` 包提供，避免入口文件承担业务实现。
"""

from contextlib import asynccontextmanager
from fastapi import Depends, FastAPI
from slowapi import _rate_limit_exceeded_handler
from slowapi.errors import RateLimitExceeded
from slowapi.middleware import SlowAPIMiddleware

from api.routes import document, analysis
from api.auth import require_service_token
from api.limiter import limiter
from config.settings import settings


@asynccontextmanager
async def lifespan(app):
    """在应用生命周期内启动 RabbitMQ 消费者。

    Args:
        app: FastAPI 应用实例。当前生命周期钩子不直接修改实例状态，保留该参数
            是为了符合 FastAPI 的生命周期接口。

    Yields:
        应用启动完成后的控制权；应用退出时显式关闭消费者连接。

    Notes:
        RabbitMQ 不可用时仍允许 HTTP 路由启动，因为文档上传和分析接口可以在
        基础设施恢复后继续工作；异步分析消费者则会在进程重启时重新建立。
    """
    # 消费者只在应用生命周期内运行；导入放在钩子内部，避免纯 HTTP 启动时过早建立连接。
    from services.mq_consumer import start_consumer, stop_consumer
    try:
        # RabbitMQ 可用时立即接收异步分析任务。
        await start_consumer()
    except Exception as e:
        import logging
        # 消息队列暂时不可用时保留 HTTP 能力，便于健康检查和基础接口先启动。
        logging.warning("RabbitMQ 连接失败，仅HTTP模式: %s", e)
    try:
        # 将控制权交还 FastAPI，服务在这里持续处理 HTTP 请求。
        yield
    finally:
        # 无论正常退出还是启动后的异常退出，都主动关闭消费者连接和后台任务。
        await stop_consumer()

# 在入口集中安装限流器和路由，业务实现继续留在 api/routes 与 services 包中。
app = FastAPI(title="The Dehydrator AI Service", version="0.1.0", lifespan=lifespan)
app.state.limiter = limiter
app.add_exception_handler(RateLimitExceeded, _rate_limit_exceeded_handler)
app.add_middleware(SlowAPIMiddleware)

# 文档和分析路由共享内部服务令牌依赖，健康检查则故意保持公开以便基础设施探活。
app.include_router(
    document.router,
    prefix="/api/document",
    tags=["document"],
    dependencies=[Depends(require_service_token)],
)
app.include_router(
    analysis.router,
    prefix="/api/analysis",
    tags=["analysis"],
    dependencies=[Depends(require_service_token)],
)


@app.get("/health")
async def health():
    """返回 AI Service 进程存活状态。

    Returns:
        包含固定 `status=ok` 的健康检查响应。该检查只表示进程可响应，
        不保证 RabbitMQ、Qdrant 或模型服务当前可用。
    """
    # 这里只证明 FastAPI 进程能响应，不主动访问 RabbitMQ、Qdrant 或模型服务。
    return {"status": "ok"}


if __name__ == "__main__":
    import uvicorn
    # 直接运行脚本时使用 env 中的监听地址；生产部署也可以由 ASGI 服务器导入 app。
    uvicorn.run(
        "main:app",
        host=settings.ai_service_host,
        port=settings.ai_service_port,
        reload=False,
    )
