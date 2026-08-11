"""文档文本切分策略。"""

from langchain_text_splitters import RecursiveCharacterTextSplitter


def chunk_text(text: str, chunk_size: int, chunk_overlap: int) -> list[str]:
    """按 Business Service 传入的处理参数切分文档。

    Args:
        text: 待切分的文档文本。
        chunk_size: 每个片段的最大字符数。
        chunk_overlap: 相邻片段的重叠字符数。

    Returns:
        按原文顺序生成的文本片段。

    Raises:
        ValueError: 分块参数超出允许范围。
    """
    if not 500 <= chunk_size <= 8000:
        raise ValueError("chunk_size 必须在 500 到 8000 之间")
    if not 0 <= chunk_overlap < chunk_size:
        raise ValueError("chunk_overlap 必须满足 0 <= overlap < chunk_size")
    splitter = RecursiveCharacterTextSplitter(
        chunk_size=chunk_size,
        chunk_overlap=chunk_overlap,
        separators=["\n\n", "\n", "。", "；", " ", ""],
    )
    docs = splitter.create_documents([text])
    return [doc.page_content for doc in docs]
