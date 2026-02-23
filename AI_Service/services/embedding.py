from langchain_community.embeddings import ZhipuAIEmbeddings
from config.settings import settings

_embeddings = None


def get_embeddings(api_key: str | None = None) -> ZhipuAIEmbeddings:
    key = api_key or settings.zhipuai_api_key
    if api_key:
        return ZhipuAIEmbeddings(model="embedding-3", api_key=key)
    global _embeddings
    if _embeddings is None:
        _embeddings = ZhipuAIEmbeddings(model="embedding-3", api_key=key)
    return _embeddings


def embed_texts(texts: list[str], api_key: str | None = None) -> list[list[float]]:
    return get_embeddings(api_key).embed_documents(texts)


def embed_query(text: str) -> list[float]:
    return get_embeddings().embed_query(text)
