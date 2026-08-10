import logging
import redis
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


def stream_invoke(llm, prompt: str, task_id: str, step: str) -> str:
    """流式调用 LLM 并返回完整内容，不发布模型输出事件。

    Args:
        llm: 支持 stream 调用的聊天模型。
        prompt: 发给模型的提示词。
        task_id: 分析任务 ID，用于取消检查。
        step: 当前分析步骤，保留该参数以兼容调用方。

    Returns:
        模型生成的完整文本。
    """
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
    if is_cancelled(task_id):
        raise AnalysisCancelled(task_id)
    return "".join(parts)
