import re
import json as _json
import httpx
from langchain_community.chat_models.zhipuai import ChatZhipuAI

# --- monkey-patch: ChatZhipuAI 硬编码 timeout=60，替换为可配置值 ---
from langchain_community.chat_models.zhipuai import (
    _truncate_params, _get_jwt_token, connect_sse,
    _convert_delta_to_message_chunk,
)
from langchain_core.messages import AIMessageChunk
from langchain_core.outputs import ChatGenerationChunk


def _patched_stream(self, messages, stop=None, run_manager=None, **kwargs):
    from config.settings import settings
    message_dicts, params = self._create_message_dicts(messages, stop)
    payload = {**params, **kwargs, "messages": message_dicts, "stream": True}
    _truncate_params(payload)
    headers = {
        "Authorization": _get_jwt_token(self.zhipuai_api_key),
        "Accept": "application/json",
    }
    with httpx.Client(headers=headers, timeout=httpx.Timeout(connect=30, read=settings.zhipuai_timeout, write=30, pool=30)) as client:
        with connect_sse(client, "POST", self.zhipuai_api_base, json=payload) as es:
            resp = es._response
            if resp.status_code != 200:
                raise ValueError(f"智谱API错误 {resp.status_code}: {resp.text}")
            ct = resp.headers.get("content-type", "")
            if "text/event-stream" not in ct:
                raise ValueError(f"智谱API返回非SSE响应 (Content-Type: {ct}): {resp.text}")
            for sse in es.iter_sse():
                chunk = _json.loads(sse.data)
                if not chunk["choices"]:
                    continue
                choice = chunk["choices"][0]
                usage = chunk.get("usage")
                msg = _convert_delta_to_message_chunk(choice["delta"], AIMessageChunk)
                finish = choice.get("finish_reason")
                gen_info = ({"finish_reason": finish, "token_usage": usage,
                             "model_name": chunk.get("model", "")} if finish else None)
                gen = ChatGenerationChunk(message=msg, generation_info=gen_info)
                if run_manager:
                    run_manager.on_llm_new_token(gen.text, chunk=gen)
                yield gen
                if finish:
                    break

ChatZhipuAI._stream = _patched_stream
# --- end monkey-patch ---


def strip_markdown_json(text: str) -> str:
    """去除 LLM 返回的 ```json ... ``` 包裹"""
    m = re.search(r"```(?:json)?\s*\n?(.*?)```", text, re.DOTALL)
    return m.group(1).strip() if m else text.strip()
