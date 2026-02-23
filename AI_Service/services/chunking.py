from langchain_text_splitters import RecursiveCharacterTextSplitter
from config.settings import settings


def chunk_text(text: str, chunk_size: int | None = None, chunk_overlap: int | None = None) -> list[str]:
    splitter = RecursiveCharacterTextSplitter(
        chunk_size=chunk_size or settings.chunk_size,
        chunk_overlap=chunk_overlap or settings.chunk_overlap,
        separators=["\n\n", "\n", "。", "；", " ", ""],
    )
    docs = splitter.create_documents([text])
    return [doc.page_content for doc in docs]
