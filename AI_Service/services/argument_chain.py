import json
from concurrent.futures import ThreadPoolExecutor, as_completed
from langchain_community.chat_models import ChatZhipuAI
from config.settings import settings
from prompts.argument_chain import MAP_PROMPT, REDUCE_PROMPT
from services.stream_publisher import stream_invoke


def _get_llm(api_key: str | None = None, model: str | None = None):
    return ChatZhipuAI(
        model=model or settings.zhipuai_model,
        api_key=api_key or settings.zhipuai_api_key,
        temperature=0.1,
        streaming=True,
    )


def extract_argument_chain(chunks: list[str], task_id: str = "", on_progress=None,
                           api_key: str | None = None, model: str | None = None,
                           map_workers: int | None = None) -> dict:
    total = len(chunks)
    mapped = [None] * total
    completed_count = 0

    def process_chunk(i, chunk):
        llm = _get_llm(api_key, model)
        return i, stream_invoke(llm, MAP_PROMPT.format(text=chunk), task_id, f"argument_chain_map_{i}")

    with ThreadPoolExecutor(max_workers=map_workers or settings.map_workers) as executor:
        futures = {executor.submit(process_chunk, i, c): i for i, c in enumerate(chunks)}
        for future in as_completed(futures):
            idx, text = future.result()
            mapped[idx] = text
            completed_count += 1
            if on_progress:
                on_progress(10 + int((completed_count / max(total, 1)) * 50), f"论据链提取 MAP ({completed_count}/{total})")

    combined = "\n".join(mapped)
    if on_progress:
        on_progress(65, "论据链提取 REDUCE")
    llm = _get_llm(api_key, model)
    text = stream_invoke(llm, REDUCE_PROMPT.format(arguments=combined), task_id, "argument_chain_reduce")
    from services import strip_markdown_json
    try:
        return json.loads(strip_markdown_json(text))
    except json.JSONDecodeError:
        return {"raw": text}
