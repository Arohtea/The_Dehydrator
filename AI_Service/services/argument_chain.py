from concurrent.futures import ThreadPoolExecutor, as_completed
from prompts.argument_chain import MAP_PROMPT, REDUCE_PROMPT
from services.llm import get_chat_model
from services.model_config import AIModelConfig
from services.stream_publisher import stream_invoke
from services.output_models import ArgumentChainResult, parse_and_validate


def extract_argument_chain(chunks: list[str], text_config: AIModelConfig,
                           task_id: str = "", on_progress=None,
                           map_workers: int = None) -> dict:
    """从文档片段中提取并归并论据链。

    Args:
        chunks: 文档片段。
        text_config: 本次分析使用的文本模型配置。
        task_id: 分析任务 ID，用于进度流和取消检查。
        on_progress: 进度回调。
        map_workers: MAP 阶段最大并发数。

    Returns:
        结构化论据链。
    """
    total = len(chunks)
    mapped = [None] * total
    completed_count = 0

    def process_chunk(i, chunk):
        llm = get_chat_model(text_config)
        return i, stream_invoke(llm, MAP_PROMPT.format(text=chunk), task_id, f"argument_chain_map_{i}")

    if map_workers is None:
        raise ValueError("缺少数据库配置 mapWorkers")
    with ThreadPoolExecutor(max_workers=map_workers) as executor:
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
    llm = get_chat_model(text_config)
    text = stream_invoke(llm, REDUCE_PROMPT.format(arguments=combined), task_id, "argument_chain_reduce")
    from services import strip_markdown_json
    return parse_and_validate(ArgumentChainResult, strip_markdown_json(text))
