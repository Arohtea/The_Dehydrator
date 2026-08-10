import json

from concurrent.futures import ThreadPoolExecutor, as_completed
from tavily import TavilyClient
from config.settings import settings
from services.llm import get_chat_model
from services.model_config import AIModelConfig
from services.vector_store import search_reference_library
from prompts.cross_validation import CROSS_VALIDATION_PROMPT
from services.stream_publisher import AnalysisCancelled, is_cancelled, stream_invoke
from services.output_models import CrossValidationResult, parse_and_validate


MAX_WEB_QUERY_CHARS = 2_000
MAX_WEB_EVIDENCE_CHARS = 12_000
MAX_WEB_CONTENT_CHARS = 2_000


def _normalize_web_sources(results: list[dict]) -> list[dict]:
    sources = []
    seen_urls = set()
    for result in results[:5]:
        title = str(result.get("title") or "未命名网页").strip()
        url = str(result.get("url") or "").strip()
        if url and url in seen_urls:
            continue
        if url:
            seen_urls.add(url)
        score = result.get("score")
        sources.append({
            "title": title,
            "url": url,
            "content": str(result.get("content") or "").strip()[:MAX_WEB_CONTENT_CHARS],
            "score": float(score) if isinstance(score, (int, float)) else None,
        })
    return sources


def _public_web_sources(results: list[dict]) -> list[dict]:
    return [
        {"title": result.get("title", ""), "url": result.get("url", "")}
        for result in results[:5]
    ]


def _format_web_evidence(results: list[dict]) -> str:
    evidence = []
    for result in results[:5]:
        title = str(result.get("title") or "未命名网页").strip()
        url = str(result.get("url") or "").strip()
        content = str(result.get("content") or "").strip()
        score = result.get("score")
        score_text = f"{score:.4f}" if isinstance(score, (int, float)) else "未知"
        evidence.append(
            f"标题：{title}\n"
            f"URL：{url}\n"
            f"相关度：{score_text}\n"
            f"内容摘要：{content}"
        )
    if not evidence:
        return "未检索到相关联网证据"
    return "\n\n".join(evidence)[:MAX_WEB_EVIDENCE_CHARS]


def _tavily_error_message(error: Exception) -> str:
    error_name = type(error).__name__
    if error_name in {"InvalidAPIKeyError", "MissingAPIKeyError", "KeylessUnsupportedEndpointError"}:
        return "Tavily API Key 无效或已失效"
    if error_name in {"UsageLimitExceededError", "TavilyKeylessLimitError"}:
        return "Tavily API 额度不足或请求频率受限"
    if "Timeout" in error_name:
        return "Tavily 联网搜索超时"
    if error_name in {"BadRequestError", "ForbiddenError"}:
        return "Tavily 联网搜索请求被拒绝"
    return "Tavily 联网搜索失败"


def _web_search(query: str, task_id: str, tavily_api_key: str | None) -> list[dict]:
    if not isinstance(tavily_api_key, str) or not tavily_api_key.strip():
        raise ValueError("未配置 Tavily API Key")
    if is_cancelled(task_id):
        raise AnalysisCancelled(task_id)

    client = None
    try:
        client = TavilyClient(api_key=tavily_api_key.strip())
        response = client.search(
            query=query[:MAX_WEB_QUERY_CHARS],
            search_depth="advanced",
            max_results=5,
            topic="general",
            include_answer=False,
            include_raw_content=False,
            timeout=settings.ai_tavily_timeout_seconds,
        )
    except Exception as error:
        raise RuntimeError(_tavily_error_message(error)) from None
    finally:
        if client is not None:
            try:
                client.close()
            except Exception:
                pass

    if is_cancelled(task_id):
        raise AnalysisCancelled(task_id)
    return _normalize_web_sources(response.get("results", []))


def _validate_single_claim(claim: str, task_id: str, idx: int,
                           text_config: AIModelConfig,
                           vector_config: AIModelConfig,
                           tavily_api_key: str | None = None,
                           mode: str = "deep",
                           doc_id: str | None = None,
                           reference_library_ids: list[str] | None = None) -> dict:
    quick_mode = mode == "quick"
    document_evidence_label = "模型自身知识判断要求"
    document_evidence = (
        "请基于你已有的通用学术知识判断该论据，不要把当前上传论文当作验证依据。"
        "若参考资料或联网搜索提供了额外信息，可以一并纳入判断。"
    )
    local_evidence_summary_hint = "模型知识摘要"
    reference_results = search_reference_library(
        claim,
        reference_library_ids or [],
        vector_config=vector_config,
        top_k=3,
    )
    reference_evidence = "\n".join(r["text"] for r in reference_results) or "未提供参考资料"
    web_results = [] if quick_mode else _web_search(claim, task_id, tavily_api_key)
    public_web_sources = _public_web_sources(web_results)
    web_evidence = "未执行联网验证（快速分析模式）" if quick_mode else _format_web_evidence(web_results)
    mode_note = (
        "当前为快速分析模式：只能基于模型自身知识与可选参考资料判断，"
        "禁止把当前上传论文内容当作验证证据，不执行联网搜索。"
        if quick_mode
        else "当前为深度分析模式：需同时参考模型自身知识、可选参考资料检索与联网搜索结果，"
             "禁止把当前上传论文内容当作验证证据。"
    )

    llm = get_chat_model(text_config)
    text = stream_invoke(llm, CROSS_VALIDATION_PROMPT.format(
        claim=claim,
        document_evidence_label=document_evidence_label,
        document_evidence=document_evidence,
        reference_evidence=reference_evidence,
        web_evidence=web_evidence,
        mode_note=mode_note,
        local_evidence_summary_hint=local_evidence_summary_hint,
    ), task_id, f"cross_validation_{idx}")
    from services import strip_markdown_json
    try:
        result = parse_and_validate(CrossValidationResult, strip_markdown_json(text))
    except ValueError:
        return {
            "claim": claim,
            "verification_status": "unverifiable",
            "confidence": 0,
            "local_evidence_summary": "结果不可用，模型输出无法解析",
            "reference_evidence_summary": "已提供参考资料" if reference_results else "未提供参考资料",
            "web_evidence_summary": "未执行联网验证（快速分析模式）" if quick_mode else "结果不可用",
            "web_sources": public_web_sources,
            "contradictions": [],
            "supplements": [],
            "conclusion": "本条交叉验证结果不可用，请重新分析",
        }
    if not result.get("local_evidence_summary"):
        result["local_evidence_summary"] = "已基于模型自身通用知识进行判断"
    if not result.get("reference_evidence_summary"):
        result["reference_evidence_summary"] = "未提供参考资料" if not reference_results else "已结合参考资料检索结果"
    if quick_mode and not result.get("web_evidence_summary"):
        result["web_evidence_summary"] = "未执行联网验证（快速分析模式）"
    result["web_sources"] = public_web_sources
    return result


def cross_validate(argument_chain: dict, text_config: AIModelConfig,
                   vector_config: AIModelConfig, task_id: str = "", on_progress=None,
                   tavily_api_key: str | None = None,
                   map_workers: int | None = None, mode: str = "deep",
                   doc_id: str | None = None,
                   reference_library_ids: list[str] | None = None) -> list[dict]:
    """并发核验论据，并在深度模式下使用 Tavily 获取联网证据。

    Args:
        argument_chain: 待核验的论据链。
        task_id: 分析任务 ID，用于进度流和取消检查。
        on_progress: 单条论据完成后的进度回调。
        text_config: 文本模型配置。
        vector_config: 向量模型配置。
        tavily_api_key: 仅供深度分析使用的 Tavily API Key。
        map_workers: 最大并发核验数。
        mode: 分析模式，quick 或 deep。
        doc_id: 当前分析文档 ID。
        reference_library_ids: 参与核验的参考资料集 ID。

    Returns:
        与输入论据顺序一致的交叉验证结果。

    Raises:
        ValueError: 深度分析未提供 Tavily API Key。
        RuntimeError: Tavily 搜索失败。
    """
    if mode != "quick" and (
            not isinstance(tavily_api_key, str) or not tavily_api_key.strip()):
        raise ValueError("未配置 Tavily API Key")
    claims = []
    for step in argument_chain.get("argument_chain", []):
        claims.append(step.get("claim", ""))
    if not claims and argument_chain.get("main_conclusion"):
        claims = [argument_chain["main_conclusion"]]
    valid_claims = [c for c in claims if c][:100]
    if not valid_claims:
        return []

    total = len(valid_claims)
    results = [None] * total
    completed_count = 0
    if map_workers is None:
        raise ValueError("缺少数据库配置 mapWorkers")
    worker_count = max(1, min(map_workers, total))

    with ThreadPoolExecutor(max_workers=worker_count) as executor:
        futures = {
            executor.submit(
                _validate_single_claim,
                claim,
                task_id,
                i,
                text_config,
                vector_config,
                tavily_api_key,
                mode,
                doc_id,
                reference_library_ids,
            ): i
            for i, claim in enumerate(valid_claims)
        }
        for future in as_completed(futures):
            idx = futures[future]
            results[idx] = future.result()
            completed_count += 1
            if on_progress:
                label = "正在进行本地交叉验证" if mode == "quick" else "正在进行联网交叉验证"
                on_progress(
                    80 + int((completed_count / total) * 15),
                    f"{label}（{completed_count}/{total}）",
                )
    return results
