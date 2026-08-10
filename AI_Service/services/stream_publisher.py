import json
import redis
from config.settings import settings

_redis = None


def _get_redis():
    global _redis
    if _redis is None:
        _redis = redis.Redis(
            host=settings.redis_host,
            port=settings.redis_port,
            password=settings.redis_password or None,
            decode_responses=True,
        )
    return _redis


class AnalysisCancelled(Exception):
    pass


def is_cancelled(task_id: str) -> bool:
    return _get_redis().exists(f"{settings.redis_cancel_prefix}{task_id}") == 1


def publish_token(task_id: str, step: str, token: str):
    _get_redis().publish(f"{settings.redis_stream_prefix}{task_id}", json.dumps({
        "step": step, "token": token,
    }))


def publish_step_done(task_id: str, step: str):
    _get_redis().publish(f"{settings.redis_stream_prefix}{task_id}", json.dumps({
        "step": step, "done": True,
    }))


def stream_invoke(llm, prompt: str, task_id: str, step: str) -> str:
    """流式调用 LLM，逐 token 发布到 Redis，返回完整内容"""
    if is_cancelled(task_id):
        raise AnalysisCancelled(task_id)
    parts = []
    chunk_count = 0
    for chunk in llm.stream(prompt):
        chunk_count += 1
        # 节流取消检查，避免每个 token 都访问一次 Redis。
        if chunk_count % settings.ai_cancel_check_interval_tokens == 0 and is_cancelled(task_id):
            raise AnalysisCancelled(task_id)
        if chunk.content:
            parts.append(chunk.content)
            publish_token(task_id, step, chunk.content)
    if is_cancelled(task_id):
        raise AnalysisCancelled(task_id)
    publish_step_done(task_id, step)
    return "".join(parts)
