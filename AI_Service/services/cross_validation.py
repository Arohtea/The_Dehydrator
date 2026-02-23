import json
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
                           api_key: str | None = None, model: str | None = None) -> dict:
    local_results = search_similar(claim, top_k=3)
    local_evidence = "\n".join(r["text"] for r in local_results) or "无相关内容"
    web_evidence = _web_search(claim, task_id, idx, api_key, model)

    llm = _get_llm(api_key, model)
    text = stream_invoke(llm, CROSS_VALIDATION_PROMPT.format(
        claim=claim,
        local_evidence=local_evidence,
        web_evidence=web_evidence,
    ), task_id, f"cross_validation_{idx}")
    from services import strip_markdown_json
    try:
        return json.loads(strip_markdown_json(text))
    except json.JSONDecodeError:
        return {"raw": text}


def cross_validate(argument_chain: dict, task_id: str = "", on_progress=None,
                   api_key: str | None = None, model: str | None = None) -> list[dict]:
    claims = []
    for step in argument_chain.get("argument_chain", []):
        claims.append(step.get("claim", ""))
    if not claims and argument_chain.get("main_conclusion"):
        claims = [argument_chain["main_conclusion"]]
    valid = [c for c in claims if c]
    total = len(valid) or 1
    results = []
    for i, c in enumerate(valid):
        if on_progress:
            on_progress(80 + int((i / total) * 15), f"交叉验证 ({i+1}/{total})")
        results.append(_validate_single_claim(c, task_id, i, api_key, model))
    return results
