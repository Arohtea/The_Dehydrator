"""论据链提取及漏洞结果回标服务。"""

import re

from concurrent.futures import ThreadPoolExecutor, as_completed
from prompts.argument_chain import MAP_PROMPT, REDUCE_PROMPT
from services.llm import get_chat_model
from services.model_config import AIModelConfig
from services.stream_publisher import stream_invoke
from services.output_models import ArgumentChainResult, parse_and_validate


_SEVERITY_RANK = {"low": 1, "medium": 2, "high": 3}


def annotate_logic_flaws(argument_chain: dict, logic_flaw_result: dict) -> dict:
    """把逻辑漏洞结果映射为论据步骤上的轻量风险标记。

    Args:
        argument_chain: 已完成结构化的论据链。
        logic_flaw_result: 逻辑漏洞检测结果。

    Returns:
        增加步骤风险标记后的论据链。原有论据文本不会被删除或改写。

    Notes:
        模型有时只返回 `location` 文本而没有结构化的 `step_numbers`，因此这里
        同时支持两种格式，并以严重程度最高的漏洞作为步骤的汇总级别。
    """
    steps = argument_chain.get("argument_chain")
    flaws = logic_flaw_result.get("flaws") if isinstance(logic_flaw_result, dict) else None
    if not isinstance(steps, list) or not isinstance(flaws, list):
        return argument_chain

    flaws_by_step = {}
    for flaw in flaws:
        if not isinstance(flaw, dict):
            continue
        step_numbers = flaw.get("step_numbers")
        if not isinstance(step_numbers, list) or not step_numbers:
            location = str(flaw.get("location") or "")
            step_numbers = [int(value) for value in re.findall(r"\d+", location)]
        severity = flaw.get("severity") if flaw.get("severity") in _SEVERITY_RANK else "low"
        for step_number in step_numbers:
            try:
                normalized_step = int(step_number)
            except (TypeError, ValueError):
                continue
            flaws_by_step.setdefault(normalized_step, []).append(severity)

    for index, step in enumerate(steps, start=1):
        if not isinstance(step, dict):
            continue
        try:
            step_number = int(step.get("step"))
        except (TypeError, ValueError):
            step_number = index
        severities = flaws_by_step.get(step_number, [])
        step["logic_flaw"] = bool(severities)
        step["logic_flaw_count"] = len(severities)
        step["logic_flaw_severity"] = max(
            severities,
            key=lambda severity: _SEVERITY_RANK[severity],
            default=None,
        )
    return argument_chain


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

    Raises:
        ValueError: 未提供并发配置，或模型输出无法通过结构校验。
        AnalysisCancelled: 在任一流式模型调用期间发现任务已取消。

    Notes:
        MAP 阶段并发处理各片段，REDUCE 阶段再把片段结果合并；两阶段之间使用
        `mapped` 的原始索引恢复文档顺序，避免线程完成顺序影响最终论据链。
    """
    total = len(chunks)
    mapped = [None] * total
    completed_count = 0

    def process_chunk(i, chunk):
        """处理一个文档片段并保留其原始索引。

        在线程池中完成单片段 LLM 调用；返回索引是为了让并发完成顺序不会改变
        文档原始顺序。
        """
        llm = get_chat_model(text_config)
        return i, stream_invoke(llm, MAP_PROMPT.format(text=chunk), task_id, f"argument_chain_map_{i}")

    if map_workers is None:
        raise ValueError("缺少数据库配置 mapWorkers")
    # MAP 阶段只处理局部上下文，适合并发；进度从 10% 到 60%，为后续归并预留区间。
    with ThreadPoolExecutor(max_workers=map_workers) as executor:
        futures = {executor.submit(process_chunk, i, c): i for i, c in enumerate(chunks)}
        for future in as_completed(futures):
            idx, text = future.result()
            mapped[idx] = text
            completed_count += 1
            if on_progress:
                on_progress(
                    10 + int((completed_count / max(total, 1)) * 50),
                    f"正在从文档片段提取论据（{completed_count}/{total}）",
                )

    # REDUCE 阶段必须使用按原文顺序拼接的结果，否则模型可能误判论据之间的关系。
    combined = "\n".join(mapped)
    if on_progress:
        on_progress(65, "正在整理完整论据链")
    llm = get_chat_model(text_config)
    text = stream_invoke(llm, REDUCE_PROMPT.format(arguments=combined), task_id, "argument_chain_reduce")
    from services import strip_markdown_json
    return parse_and_validate(ArgumentChainResult, strip_markdown_json(text))
