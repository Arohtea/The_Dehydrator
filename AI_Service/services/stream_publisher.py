import json
import redis
from config.settings import settings

_redis = None


def _get_redis():
    global _redis
    if _redis is None:
        _redis = redis.Redis(host=settings.redis_host, port=settings.redis_port, decode_responses=True)
    return _redis


class AnalysisCancelled(Exception):
    pass


def is_cancelled(task_id: str) -> bool:
    return _get_redis().exists(f"analysis:cancel:{task_id}") == 1


def publish_token(task_id: str, step: str, token: str):
    _get_redis().publish(f"analysis:stream:{task_id}", json.dumps({
        "step": step, "token": token,
    }))


def publish_step_done(task_id: str, step: str):
    _get_redis().publish(f"analysis:stream:{task_id}", json.dumps({
        "step": step, "done": True,
    }))


def stream_invoke(llm, prompt: str, task_id: str, step: str) -> str:
    """流式调用 LLM，逐 token 发布到 Redis，返回完整内容"""
    if is_cancelled(task_id):
        raise AnalysisCancelled(task_id)
    content = ""
    for chunk in llm.stream(prompt):
        if is_cancelled(task_id):
            raise AnalysisCancelled(task_id)
        if chunk.content:
            content += chunk.content
            publish_token(task_id, step, chunk.content)
    publish_step_done(task_id, step)
    return content
