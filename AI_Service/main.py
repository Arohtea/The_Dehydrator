from contextlib import asynccontextmanager
from fastapi import Depends, FastAPI
from slowapi import _rate_limit_exceeded_handler
from slowapi.errors import RateLimitExceeded
from slowapi.middleware import SlowAPIMiddleware

from api.routes import document, analysis
from api.auth import require_service_token
from api.limiter import limiter


@asynccontextmanager
async def lifespan(app):
    from services.mq_consumer import start_consumer
    try:
        await start_consumer()
    except Exception as e:
        import logging
        logging.warning("RabbitMQ 连接失败，仅HTTP模式: %s", e)
    yield

app = FastAPI(title="The Dehydrator AI Service", version="0.1.0", lifespan=lifespan)
app.state.limiter = limiter
app.add_exception_handler(RateLimitExceeded, _rate_limit_exceeded_handler)
app.add_middleware(SlowAPIMiddleware)

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
    return {"status": "ok"}


if __name__ == "__main__":
    import uvicorn
    uvicorn.run("main:app", host="0.0.0.0", port=8000, reload=False)
