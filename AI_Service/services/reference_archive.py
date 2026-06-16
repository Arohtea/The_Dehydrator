import json

from langchain_community.chat_models import ChatZhipuAI

from config.settings import settings
from prompts.reference_classification import REFERENCE_CLASSIFICATION_PROMPT
from services import strip_markdown_json
from services.vector_store import clone_analysis_document_to_reference


def _get_llm(api_key: str | None = None, model: str | None = None):
    return ChatZhipuAI(
        model=model or settings.zhipuai_model,
        api_key=api_key or settings.zhipuai_api_key,
        temperature=0.1,
    )


def _preview_text(chunks: list[str], max_chunks: int = 6, max_chars: int = 5000) -> str:
    joined = "\n\n".join(chunk for chunk in chunks[:max_chunks] if chunk)
    return joined[:max_chars] if joined else "无可用文档片段"


def _format_candidates(items: list[str]) -> str:
    return "\n".join(f"- {item}" for item in items) if items else "无"


def _suggest_folder_and_category(filename: str, document_preview: str,
                                 folder_candidates: list[str], category_candidates: list[str],
                                 api_key: str | None = None, model: str | None = None) -> dict:
    llm = _get_llm(api_key, model)
    response = llm.invoke(REFERENCE_CLASSIFICATION_PROMPT.format(
        filename=filename,
        document_preview=document_preview,
        folder_candidates=_format_candidates(folder_candidates),
        category_candidates=_format_candidates(category_candidates),
    ))
    content = response.content if isinstance(response.content, str) else "".join(
        item.get("text", "") for item in response.content if isinstance(item, dict)
    )
    result = json.loads(strip_markdown_json(content))
    return {
        "folder_name": result.get("folder_name"),
        "category_name": result.get("category_name"),
        "confidence": result.get("confidence", 0.0),
        "reason": result.get("reason", ""),
    }


def archive_reference_document(doc_id: str, library_id: str, filename: str,
                               folder_candidates: list[str], category_candidates: list[str],
                               api_key: str | None = None, model: str | None = None) -> dict:
    cloned_doc_id, texts = clone_analysis_document_to_reference(doc_id, library_id)
    try:
        suggestion = _suggest_folder_and_category(
            filename,
            _preview_text(texts),
            folder_candidates,
            category_candidates,
            api_key,
            model,
        )
    except Exception:
        suggestion = {
            "folder_name": None,
            "category_name": None,
            "confidence": 0.0,
            "reason": "AI 分类失败，已保留默认归档位置",
        }

    return {
        "doc_id": cloned_doc_id,
        **suggestion,
    }
