from contextlib import asynccontextmanager
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from api.routes import document, analysis


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

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(document.router, prefix="/api/document", tags=["document"])
app.include_router(analysis.router, prefix="/api/analysis", tags=["analysis"])


@app.get("/health")
async def health():
    return {"status": "ok"}


if __name__ == "__main__":
    import uvicorn
    uvicorn.run("main:app", host="0.0.0.0", port=8000, reload=True)
