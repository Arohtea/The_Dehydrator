import json
import logging
import redis
import time
from config.settings import settings

_redis = None
log = logging.getLogger(__name__)


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
    try:
        return _get_redis().exists(f"{settings.redis_cancel_prefix}{task_id}") == 1
    except redis.RedisError:
        log.warning("取消信号检查失败，将由任务结果和轮询兜底: %s", task_id)
        return False


def _publish_event(task_id: str, event: dict):
    try:
        client = _get_redis()
        key = f"{settings.redis_stream_prefix}{task_id}"
        client.xadd(
            key,
            {"data": json.dumps(event, ensure_ascii=False)},
            maxlen=settings.ai_redis_stream_max_length,
            approximate=True,
        )
        client.expire(key, settings.ai_redis_stream_ttl_seconds)
    except redis.RedisError:
        log.warning("分析事件流写入失败，继续执行分析: %s", task_id)


def publish_token(task_id: str, step: str, token: str):
    _publish_event(task_id, {
        "kind": "token",
        "step": step,
        "token": token,
        "done": False,
    })


def publish_step_done(task_id: str, step: str):
    _publish_event(task_id, {
        "kind": "step_done",
        "step": step,
        "token": "",
        "done": True,
    })


def stream_invoke(llm, prompt: str, task_id: str, step: str) -> str:
    """流式调用 LLM，逐 token 发布到 Redis，返回完整内容"""
    if is_cancelled(task_id):
        raise AnalysisCancelled(task_id)
    parts = []
    chunk_count = 0
    token_buffer = []
    buffered_chars = 0
    last_flush_at = time.monotonic()

    def flush_buffer():
        nonlocal token_buffer, buffered_chars, last_flush_at
        if token_buffer:
            publish_token(task_id, step, "".join(token_buffer))
            token_buffer = []
            buffered_chars = 0
        last_flush_at = time.monotonic()

    for chunk in llm.stream(prompt):
        chunk_count += 1
        # 节流取消检查，避免每个 token 都访问一次 Redis。
        if chunk_count % settings.ai_cancel_check_interval_tokens == 0 and is_cancelled(task_id):
            raise AnalysisCancelled(task_id)
        if chunk.content:
            parts.append(chunk.content)
            token_buffer.append(chunk.content)
            buffered_chars += len(chunk.content)
            if (
                buffered_chars >= settings.ai_stream_batch_chars
                or (time.monotonic() - last_flush_at) * 1000 >= settings.ai_stream_batch_ms
            ):
                flush_buffer()
    flush_buffer()
    if is_cancelled(task_id):
        raise AnalysisCancelled(task_id)
    publish_step_done(task_id, step)
    return "".join(parts)
