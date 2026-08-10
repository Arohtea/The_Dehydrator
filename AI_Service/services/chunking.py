from langchain_text_splitters import RecursiveCharacterTextSplitter
from config.settings import settings


def chunk_text(text: str, chunk_size: int | None = None, chunk_overlap: int | None = None) -> list[str]:
    size = chunk_size if chunk_size is not None else settings.chunk_size
    overlap = chunk_overlap if chunk_overlap is not None else settings.chunk_overlap
    if not 500 <= size <= 8000:
        raise ValueError("chunk_size 必须在 500 到 8000 之间")
    if not 0 <= overlap < size:
        raise ValueError("chunk_overlap 必须满足 0 <= overlap < chunk_size")
    splitter = RecursiveCharacterTextSplitter(
        chunk_size=size,
        chunk_overlap=overlap,
        separators=["\n\n", "\n", "。", "；", " ", ""],
    )
    docs = splitter.create_documents([text])
    return [doc.page_content for doc in docs]
