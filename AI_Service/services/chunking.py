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
    # 分块大小由 Business Service 的系统设置传入，先在这里拒绝过小或过大的值。
    if not 500 <= chunk_size <= 8000:
        raise ValueError("chunk_size 必须在 500 到 8000 之间")
    # 重叠只能复用前一片段内容，不能等于或超过片段本身，否则切分器无法前进。
    if not 0 <= chunk_overlap < chunk_size:
        raise ValueError("chunk_overlap 必须满足 0 <= overlap < chunk_size")
    # 优先按段落、换行、句号和分号断开，尽量保留自然语义；最后才按空格或字符硬切。
    splitter = RecursiveCharacterTextSplitter(
        chunk_size=chunk_size,
        chunk_overlap=chunk_overlap,
        separators=["\n\n", "\n", "。", "；", " ", ""],
    )
    # 创建 Document 后只取 page_content，payload 中保存的就是可直接展示和检索的文本。
    docs = splitter.create_documents([text])
    return [doc.page_content for doc in docs]
