import json
from concurrent.futures import ThreadPoolExecutor, as_completed
from langchain_community.chat_models import ChatZhipuAI
from config.settings import settings
from services.vector_store import search_similar
from prompts.cross_validation import CROSS_VALIDATION_PROMPT
from services.stream_publisher import stream_invoke


def _get_llm(api_key: str | None = None, model: str | None = None, **kwargs):
    return ChatZhipuAI(
        model=model or settings.zhipuai_model,
        api_key=api_key or settings.zhipuai_api_key,
        temperature=0.1,
        streaming=True,
        **kwargs,
    )


def _web_search(query: str, task_id: str, idx: int,
                api_key: str | None = None, model: str | None = None) -> str:
    llm = _get_llm(api_key, model, model_kwargs={"tools": [{"type": "web_search", "web_search": {"enable": True}}]})
    return stream_invoke(llm, f"请搜索以下内容的最新信息并总结：{query}", task_id, f"web_search_{idx}")


def _validate_single_claim(claim: str, task_id: str, idx: int,
                           api_key: str | None = None, model: str | None = None,
                           mode: str = "deep") -> dict:
    local_results = search_similar(claim, top_k=3)
    local_evidence = "\n".join(r["text"] for r in local_results) or "无相关内容"
    quick_mode = mode == "quick"
    web_evidence = "未执行联网验证（快速分析模式）" if quick_mode else _web_search(claim, task_id, idx, api_key, model)
    web_evidence_note = "当前为快速分析模式，本次结论仅基于本地知识库检索，不包含联网搜索结果。" if quick_mode else "当前为深度分析模式，需同时参考本地知识库与联网搜索结果。"

    llm = _get_llm(api_key, model)
    text = stream_invoke(llm, CROSS_VALIDATION_PROMPT.format(
        claim=claim,
        local_evidence=local_evidence,
        web_evidence=web_evidence,
        web_evidence_note=web_evidence_note,
    ), task_id, f"cross_validation_{idx}")
    from services import strip_markdown_json
    try:
        result = json.loads(strip_markdown_json(text))
        if quick_mode and not result.get("web_evidence_summary"):
            result["web_evidence_summary"] = "未执行联网验证（快速分析模式）"
        return result
    except json.JSONDecodeError:
        return {"raw": text}


def cross_validate(argument_chain: dict, task_id: str = "", on_progress=None,
                   api_key: str | None = None, model: str | None = None,
                   map_workers: int | None = None, mode: str = "deep") -> list[dict]:
    claims = []
    for step in argument_chain.get("argument_chain", []):
        claims.append(step.get("claim", ""))
    if not claims and argument_chain.get("main_conclusion"):
        claims = [argument_chain["main_conclusion"]]
    valid_claims = [c for c in claims if c]
    if not valid_claims:
        return []

    total = len(valid_claims)
    results = [None] * total
    completed_count = 0
    worker_count = max(1, min(map_workers or settings.map_workers, total))

    with ThreadPoolExecutor(max_workers=worker_count) as executor:
        futures = {
            executor.submit(_validate_single_claim, claim, task_id, i, api_key, model, mode): i
            for i, claim in enumerate(valid_claims)
        }
        for future in as_completed(futures):
            idx = futures[future]
            results[idx] = future.result()
            completed_count += 1
            if on_progress:
                label = "交叉验证（快速）" if mode == "quick" else "交叉验证"
                on_progress(
                    80 + int((completed_count / total) * 15),
                    f"{label} ({completed_count}/{total})",
                )
    return results
