"""将分析文档向量复制为参考资料并生成归档建议。"""

from prompts.reference_classification import REFERENCE_CLASSIFICATION_PROMPT
from services import strip_markdown_json
from services.llm import get_chat_model
from services.model_config import AIModelConfig
from services.vector_store import clone_analysis_document_to_reference
from services.output_models import ReferenceClassificationResult, parse_and_validate


def _preview_text(chunks: list[str], max_chunks: int = 6, max_chars: int = 5000) -> str:
    """截取有限数量和长度的片段作为分类模型上下文。

    截断可以控制归档分类的请求成本，同时保留文档开头最可能包含标题和主题
    信息的部分；该预览不影响已经写入 Qdrant 的完整向量。
    """
    joined = "\n\n".join(chunk for chunk in chunks[:max_chunks] if chunk)
    return joined[:max_chars] if joined else "无可用文档片段"


def _format_candidates(items: list[str]) -> str:
    """把候选文件夹或分类名称转换为提示词中的列表文本。"""
    return "\n".join(f"- {item}" for item in items) if items else "无"


def _suggest_folder_and_category(filename: str, document_preview: str,
                                 folder_candidates: list[str], category_candidates: list[str],
                                 text_config: AIModelConfig) -> dict:
    """调用文本模型生成归档位置建议并校验结果结构。

    Args:
        filename: 原始文件名。
        document_preview: 用于分类的有限文档预览。
        folder_candidates: 当前资料库已有文件夹名称。
        category_candidates: 当前资料库已有分类名称。
        text_config: 本次分类调用使用的模型配置。

    Returns:
        包含文件夹、分类、置信度和原因的普通字典。

    Raises:
        ValueError: 模型输出无法解析为 `ReferenceClassificationResult`。
    """
    llm = get_chat_model(text_config, streaming=False)
    response = llm.invoke(REFERENCE_CLASSIFICATION_PROMPT.format(
        filename=filename,
        document_preview=document_preview,
        folder_candidates=_format_candidates(folder_candidates),
        category_candidates=_format_candidates(category_candidates),
    ))
    content = response.content if isinstance(response.content, str) else "".join(
        item.get("text", "") for item in response.content if isinstance(item, dict)
    )
    result = parse_and_validate(ReferenceClassificationResult, strip_markdown_json(content))
    return {
        "folder_name": result.get("folder_name"),
        "category_name": result.get("category_name"),
        "confidence": result.get("confidence", 0.0),
        "reason": result.get("reason", ""),
    }


def archive_reference_document(doc_id: str, library_id: str, filename: str,
                               folder_candidates: list[str], category_candidates: list[str],
                               text_config: AIModelConfig) -> dict:
    """克隆分析文档向量，并使用显式文本模型配置给出归档建议。

    Args:
        doc_id: 待归档的分析文档 ID。
        library_id: 目标参考资料集 ID。
        filename: 文档文件名。
        folder_candidates: 可选文件夹名称。
        category_candidates: 可选分类名称。
        text_config: 本次分类使用的文本模型配置。

    Returns:
        新参考文档 ID、分类建议和置信度。
    """
    cloned_doc_id, texts = clone_analysis_document_to_reference(doc_id, library_id)
    try:
        suggestion = _suggest_folder_and_category(
            filename,
            _preview_text(texts),
            folder_candidates,
            category_candidates,
            text_config,
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
