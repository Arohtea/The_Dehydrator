"""LLM 流式调用、公开分析依据发布和传输层重试。"""

import json
import logging
import time

import httpx
import redis
from config.settings import settings
from tenacity import retry, retry_if_exception, stop_after_attempt, wait_exponential

_redis = None
log = logging.getLogger(__name__)

PUBLIC_REASONING_OPEN_TAG = "<public_reasoning>"
PUBLIC_REASONING_CLOSE_TAG = "</public_reasoning>"
RESULT_OPEN_TAG = "<result>"
RESULT_CLOSE_TAG = "</result>"
MAX_PUBLIC_REASONING_CHARS = 4_000
STREAM_MAX_ATTEMPTS = 3


def _get_redis():
    """获取用于取消检查和分析事件流的共享 Redis 客户端。"""
    global _redis
    if _redis is None:
        # 延迟创建客户端，模块导入和健康检查不需要立即连接 Redis。
        _redis = redis.Redis(
            host=settings.redis_host,
            port=settings.redis_port,
            password=settings.redis_password or None,
            decode_responses=True,
        )
    return _redis


class AnalysisCancelled(Exception):
    """表示分析任务已收到取消信号，应停止当前模型调用。"""

    pass


def is_cancelled(task_id: str) -> bool:
    """检查任务取消标记。

    Args:
        task_id: 分析任务 ID。

    Returns:
        Redis 中存在取消 Key 时返回 `True`；Redis 暂时不可用时返回 `False`，
        由任务结果和 Business Service 轮询继续兜底。
    """
    try:
        # Java 服务把取消请求写成带 TTL 的 Key，存在即表示当前模型调用应尽快停止。
        return _get_redis().exists(f"{settings.redis_cancel_prefix}{task_id}") == 1
    except redis.RedisError:
        log.warning("取消信号检查失败，将由任务结果和轮询兜底: %s", task_id)
        return False


def _publish_event(task_id: str, event: dict):
    """把结构化事件写入任务专属 Redis Stream。

    Redis Stream 写入失败只记录日志，不中断 AI 分析；最终结果仍通过 RabbitMQ
    返回，避免实时展示通道故障扩大为任务失败。
    """
    try:
        # 每个任务拥有独立 Stream，前端只会回放当前任务的进度和公开依据。
        client = _get_redis()
        key = f"{settings.redis_stream_prefix}{task_id}"
        # data 统一保存为 JSON 字符串，与 Java SSE 控制器读取的字段契约保持一致。
        client.xadd(
            key,
            {"data": json.dumps(event, ensure_ascii=False)},
            maxlen=settings.ai_redis_stream_max_length,
            approximate=True,
        )
        # 长度限制控制单任务历史大小，TTL 防止已经结束的任务永久占用 Redis。
        client.expire(key, settings.ai_redis_stream_ttl_seconds)
    except redis.RedisError:
        # 实时 Stream 是展示旁路，写入失败不能改变 RabbitMQ 最终结果。
        log.warning("分析事件流写入失败，继续执行分析: %s", task_id)


def _publish_thinking(task_id: str, step: str, text: str = "", done: bool = False,
                     reset: bool = False):
    """发布面向用户的公开分析依据事件。

    `reset` 用于流式传输中断重试时通知前端清除上一次尝试的半截内容，防止同一
    分析步骤出现重复文本。
    """
    # 统一事件结构，reset 只在重试时出现，前端据此清掉上一轮半截文本。
    event = {
        "kind": "thinking",
        "step": step,
        "text": text,
        "done": done,
    }
    if reset:
        event["reset"] = True
    _publish_event(task_id, event)


class _PublicReasoningParser:
    """解析公开推理协议，同时保留旧格式回退所需的原始内容。"""

    def __init__(self):
        # pending 保存跨 chunk 的半截标记；模型可能把一个协议标签拆成多个网络片段。
        self._state = "search_reasoning"
        self._pending = ""
        self._result_parts = []
        self.reasoning_started = False
        self.reasoning_closed = False
        self.result_closed = False

    @staticmethod
    def _retain_marker_suffix(value: str, marker: str) -> str:
        """保留可能是协议标记前缀的尾部文本。

        LLM 的单个 chunk 可能把 `<public_reasoning>` 等标记拆开；保留最长匹配
        后缀可以在下一 chunk 到来时继续识别，而不会提前把标记内容发布给用户。
        """
        # 最多保留 marker 长度减一，确保不会把完整标记误留到下一轮。
        max_length = min(len(value), len(marker) - 1)
        for length in range(max_length, 0, -1):
            # 从最长后缀开始匹配，优先保留最完整的潜在标记前缀。
            if value.endswith(marker[:length]):
                return value[-length:]
        return ""

    def feed(self, text: str, on_reasoning_text, on_reasoning_done):
        """消费一个流式文本片段并推进公开协议状态机。

        Args:
            text: 当前收到的模型文本片段。
            on_reasoning_text: 收到安全的公开依据文本时的回调。
            on_reasoning_done: 识别到公开依据结束标记时的回调。

        Notes:
            状态机只发布 `<public_reasoning>` 内容，把 `<result>` 内容暂存到最终
            返回值；未完整识别协议时由 `output` 回退为原始模型输出。
        """
        # 先把新片段接到上次未消费的尾部，状态机始终处理连续文本而不是孤立 chunk。
        self._pending += text
        while True:
            if self._state == "search_reasoning":
                # 等待公开依据开始标记；标记前的模型前缀不向用户公开。
                index = self._pending.find(PUBLIC_REASONING_OPEN_TAG)
                if index < 0:
                    # 没找到完整标记时只留下可能属于标记的尾部，防止下个 chunk 到来前误发。
                    self._pending = self._retain_marker_suffix(
                        self._pending, PUBLIC_REASONING_OPEN_TAG)
                    return
                # 丢弃开始标记本身，进入可以安全发布文本的 reasoning 状态。
                self._pending = self._pending[index + len(PUBLIC_REASONING_OPEN_TAG):]
                self.reasoning_started = True
                self._state = "reasoning"
                continue

            if self._state == "reasoning":
                # reasoning 状态只把公开依据交给回调，结果内容继续留在内部缓冲区。
                index = self._pending.find(PUBLIC_REASONING_CLOSE_TAG)
                if index < 0:
                    # 关闭标记可能被拆开，最后那一小段必须暂存，前面的内容才可以发布。
                    safe_length = len(self._pending) - len(
                        self._retain_marker_suffix(self._pending, PUBLIC_REASONING_CLOSE_TAG))
                    if safe_length > 0:
                        on_reasoning_text(self._pending[:safe_length])
                        self._pending = self._pending[safe_length:]
                    return
                # 先发布关闭标记前的正文，再切换到查找结果开始标记。
                on_reasoning_text(self._pending[:index])
                self._pending = self._pending[index + len(PUBLIC_REASONING_CLOSE_TAG):]
                self.reasoning_closed = True
                self._state = "search_result"
                # 告知外层当前步骤的公开依据已结束，后续 result 内容不会出现在 thinking 流中。
                on_reasoning_done()
                continue

            if self._state == "search_result":
                # 结果标记之前的内容不对外发送，只继续寻找真正的结构化结果区域。
                index = self._pending.find(RESULT_OPEN_TAG)
                if index < 0:
                    self._pending = self._retain_marker_suffix(
                        self._pending, RESULT_OPEN_TAG)
                    return
                self._pending = self._pending[index + len(RESULT_OPEN_TAG):]
                self._state = "result"
                continue

            if self._state == "result":
                # 结果正文只缓存，等待完整关闭标记后一次性交给 JSON 解析器。
                index = self._pending.find(RESULT_CLOSE_TAG)
                if index < 0:
                    # 保留可能属于关闭标记的后缀，避免截断协议导致最终 JSON 损坏。
                    safe_length = len(self._pending) - len(
                        self._retain_marker_suffix(self._pending, RESULT_CLOSE_TAG))
                    if safe_length > 0:
                        self._result_parts.append(self._pending[:safe_length])
                        self._pending = self._pending[safe_length:]
                    return
                # 收到完整结果关闭标记后，结果协议完成，后续文本不再属于本次结果。
                self._result_parts.append(self._pending[:index])
                self._pending = ""
                self.result_closed = True
                self._state = "done"
                return

            return

    def output(self, raw_text: str) -> str:
        """返回协议解析后的结果内容或旧格式的原始输出。"""
        # 只有两个结束标记都收到时才使用解析后的结果；协议不完整则回退原始输出，兼容旧模型。
        if self.reasoning_closed and self.result_closed:
            return "".join(self._result_parts)
        return raw_text


def _is_retryable_stream_error(error: BaseException) -> bool:
    """只对流式响应传输层中断重试，避免掩盖模型或业务错误。"""
    # 异常可能通过 cause/context 形成链，seen 防止异常链异常时出现死循环。
    seen = set()
    current = error
    while current is not None and id(current) not in seen:
        # 仅网络传输层异常可安全重发；模型拒绝、参数错误和业务取消不能靠重试解决。
        if isinstance(current, httpx.TransportError):
            return True
        seen.add(id(current))
        current = current.__cause__ or current.__context__
    return False


def stream_invoke(llm, prompt: str, task_id: str, step: str) -> str:
    """流式调用 LLM，发布公开分析依据并返回最终 JSON 内容。

    流式响应建立后仍可能在读取过程中被上游关闭；这类传输层异常会
    重新发起完整请求，避免将一次网络抖动升级为整条分析任务失败。

    Args:
        llm: 支持 stream 调用的聊天模型。
        prompt: 发给模型的提示词。
        task_id: 分析任务 ID，用于取消检查。
        step: 当前分析步骤，保留该参数以兼容调用方。

    Returns:
        协议模式下为 ``<result>`` 标签内的内容，旧格式下为模型完整输出。
    """
    # 外层重试需要知道上一轮是否已经向前端发送过依据，只有发送过才需要 reset。
    attempt_state = {"thinking_emitted": False}

    def invoke_once():
        """执行一次完整流式请求，并在传输中断时交给外层重试装饰器处理。"""
        # 每次重试都是一条新的完整模型请求，重新计算协议状态和公开依据缓冲。
        attempt_state["thinking_emitted"] = False
        if is_cancelled(task_id):
            raise AnalysisCancelled(task_id)

        # raw_parts 用于旧格式回退；parser 则提取新协议中的公开依据和结果正文。
        raw_parts = []
        chunk_count = 0
        parser = _PublicReasoningParser()
        thinking_buffer = []
        buffered_chars = 0
        reasoning_chars = 0
        thinking_done = False
        last_flush_at = time.monotonic()

        def flush_thinking():
            """将缓存的公开依据批量写入 Redis，控制写入频率。"""
            nonlocal thinking_buffer, buffered_chars, last_flush_at
            if thinking_buffer:
                # 批量写入减少 Redis 往返，公开依据仍按生成顺序保持连续。
                attempt_state["thinking_emitted"] = True
                _publish_thinking(task_id, step, "".join(thinking_buffer))
                thinking_buffer = []
                buffered_chars = 0
            last_flush_at = time.monotonic()

        def queue_thinking(text: str):
            """截断并缓存公开依据，在达到字符或时间阈值后刷新。"""
            nonlocal buffered_chars, reasoning_chars
            # 限制公开依据总长度，防止模型长篇输出拖垮 Redis 和 SSE。
            if not text or reasoning_chars >= MAX_PUBLIC_REASONING_CHARS:
                return
            remaining = MAX_PUBLIC_REASONING_CHARS - reasoning_chars
            text = text[:remaining]
            reasoning_chars += len(text)
            thinking_buffer.append(text)
            buffered_chars += len(text)
            # 达到字符阈值或时间阈值就刷新，平衡实时性与 Redis 写入频率。
            if (
                buffered_chars >= settings.ai_stream_batch_chars
                or (time.monotonic() - last_flush_at) * 1000 >= settings.ai_stream_batch_ms
            ):
                flush_thinking()

        def finish_thinking():
            """发送当前步骤的公开依据终止事件，且确保只发送一次。"""
            nonlocal thinking_done
            # 没进入公开依据协议，或已经发过结束事件时，不重复发送 done。
            if not parser.reasoning_started or thinking_done:
                return
            flush_thinking()
            attempt_state["thinking_emitted"] = True
            _publish_thinking(task_id, step, done=True)
            thinking_done = True

        # 完整消费流式响应；只有传输异常才由 tenacity 在外层重新执行整个请求。
        for chunk in llm.stream(prompt):
            chunk_count += 1
            # 节流取消检查，避免每个 token 都访问一次 Redis。
            if chunk_count % settings.ai_cancel_check_interval_tokens == 0 and is_cancelled(task_id):
                finish_thinking()
                raise AnalysisCancelled(task_id)
            if chunk.content:
                # 原文保留给 JSON 解析和旧协议回退，parser 同时处理前端可见的 thinking。
                raw_parts.append(chunk.content)
                parser.feed(chunk.content, queue_thinking, finish_thinking)
        # 流结束时强制刷新剩余缓冲，避免最后一批公开依据滞留在内存中。
        flush_thinking()
        if is_cancelled(task_id):
            finish_thinking()
            raise AnalysisCancelled(task_id)
        # 协议完整时返回 result 区域，否则返回模型原文交给旧格式解析逻辑处理。
        return parser.output("".join(raw_parts))

    def before_retry(retry_state):
        """在传输层重试前清理前端可见的半截依据并记录尝试信息。"""
        # tenacity 只会在传输层异常时进入这里；先再次检查取消，取消优先于重试。
        error = retry_state.outcome.exception() if retry_state.outcome else None
        if is_cancelled(task_id):
            raise AnalysisCancelled(task_id)
        # 前端已经看到上一轮半截依据时，先发 reset 再开始下一轮，避免显示重复内容。
        if attempt_state["thinking_emitted"]:
            _publish_thinking(task_id, step, reset=True)
        log.warning(
            "LLM 流式响应中断，正在重试: step=%s attempt=%d/%d error_type=%s",
            step,
            retry_state.attempt_number,
            STREAM_MAX_ATTEMPTS,
            type(error).__name__ if error else "UnknownError",
        )

    # 限定最大尝试次数和指数退避，防止上游网络故障时无限占用分析线程。
    @retry(
        retry=retry_if_exception(_is_retryable_stream_error),
        stop=stop_after_attempt(STREAM_MAX_ATTEMPTS),
        wait=wait_exponential(multiplier=0.5, min=0.5, max=4),
        before_sleep=before_retry,
        reraise=True,
    )
    def invoke_with_retry():
        """在限定次数内重试可恢复的 HTTP 传输异常。"""
        return invoke_once()

    # 返回最终 JSON/原始文本；不可重试异常和耗尽重试次数会交给消息消费者处理。
    return invoke_with_retry()
