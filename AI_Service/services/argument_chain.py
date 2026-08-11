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
    # 只有标准化后的“步骤列表”和“漏洞列表”都存在时，才有安全的回标目标。
    steps = argument_chain.get("argument_chain")
    flaws = logic_flaw_result.get("flaws") if isinstance(logic_flaw_result, dict) else None
    if not isinstance(steps, list) or not isinstance(flaws, list):
        return argument_chain

    # 先把每个漏洞整理成“步骤编号 -> 严重程度列表”，后面再一次性更新步骤字段。
    flaws_by_step = {}
    for flaw in flaws:
        if not isinstance(flaw, dict):
            continue
        step_numbers = flaw.get("step_numbers")
        if not isinstance(step_numbers, list) or not step_numbers:
            # 模型没有返回结构化编号时，从“第 2 步”等位置描述中兜底提取数字。
            location = str(flaw.get("location") or "")
            step_numbers = [int(value) for value in re.findall(r"\d+", location)]
        # 未识别的严重程度不应阻断整条结果，按最低级别处理并保留漏洞存在标记。
        severity = flaw.get("severity") if flaw.get("severity") in _SEVERITY_RANK else "low"
        for step_number in step_numbers:
            try:
                # 统一转换为整数，兼容模型输出的字符串数字；非法编号只跳过当前编号。
                normalized_step = int(step_number)
            except (TypeError, ValueError):
                continue
            flaws_by_step.setdefault(normalized_step, []).append(severity)

    # 按论据链原顺序逐步回填展示字段，不改写 claim/evidence 等原始论据内容。
    for index, step in enumerate(steps, start=1):
        if not isinstance(step, dict):
            continue
        try:
            # 优先使用模型给出的步骤号；缺失或格式异常时用列表位置作为稳定兜底编号。
            step_number = int(step.get("step"))
        except (TypeError, ValueError):
            step_number = index
        severities = flaws_by_step.get(step_number, [])
        # 前端需要同时知道是否有漏洞、漏洞数量和最高严重程度，避免再次解释原始漏洞列表。
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
    # mapped 与原文片段一一对应；即使线程先后顺序不同，最终归并仍保持原文顺序。
    total = len(chunks)
    mapped = [None] * total
    completed_count = 0

    def process_chunk(i, chunk):
        """处理一个文档片段并保留其原始索引。

        在线程池中完成单片段 LLM 调用；返回索引是为了让并发完成顺序不会改变
        文档原始顺序。
        """
        # 每个 MAP 任务使用同一份请求级文本模型配置，避免并发任务读取到不同模型。
        llm = get_chat_model(text_config)
        return i, stream_invoke(llm, MAP_PROMPT.format(text=chunk), task_id, f"argument_chain_map_{i}")

    if map_workers is None:
        raise ValueError("缺少数据库配置 mapWorkers")
    # MAP 阶段只处理局部上下文，适合并发；进度从 10% 到 60%，为后续归并预留区间。
    # futures 只负责并发执行；完成顺序不用于拼接顺序，后面通过返回的原始索引回填。
    with ThreadPoolExecutor(max_workers=map_workers) as executor:
        futures = {executor.submit(process_chunk, i, c): i for i, c in enumerate(chunks)}
        for future in as_completed(futures):
            # 任一线程异常都会在 result() 处抛出，整个分析任务随即失败而不产生半截论据链。
            idx, text = future.result()
            mapped[idx] = text
            completed_count += 1
            if on_progress:
                # MAP 阶段占总进度的 10%~60%，为 REDUCE 和后续分析预留进度区间。
                on_progress(
                    10 + int((completed_count / max(total, 1)) * 50),
                    f"正在从文档片段提取论据（{completed_count}/{total}）",
                )

    # REDUCE 阶段必须使用按原文顺序拼接的结果，否则模型可能误判论据之间的关系。
    # 只有所有片段完成后才进入 REDUCE；按索引拼接能保留论据前后关系。
    combined = "\n".join(mapped)
    if on_progress:
        on_progress(65, "正在整理完整论据链")
    # REDUCE 使用完整的局部结果，负责去重、排序并输出最终结构化论据链。
    llm = get_chat_model(text_config)
    text = stream_invoke(llm, REDUCE_PROMPT.format(arguments=combined), task_id, "argument_chain_reduce")
    from services import strip_markdown_json
    # 去除 Markdown 包裹后再进行 Pydantic 校验，确保下游拿到固定字段结构。
    return parse_and_validate(ArgumentChainResult, strip_markdown_json(text))
