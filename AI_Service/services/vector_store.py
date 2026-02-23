import uuid

from qdrant_client import QdrantClient, models
from config.settings import settings
from services.embedding import embed_texts, embed_query

_client = None


def get_client() -> QdrantClient:
    global _client
    if _client is None:
        _client = QdrantClient(host=settings.qdrant_host, port=settings.qdrant_port)
    return _client


def ensure_collection(vector_size: int = 2048):
    client = get_client()
    name = settings.qdrant_collection
    try:
        if not client.collection_exists(name):
            client.create_collection(
                collection_name=name,
                vectors_config=models.VectorParams(
                    size=vector_size, distance=models.Distance.COSINE
                ),
            )
    except Exception as e:
        import logging
        logging.warning("ensure_collection 失败: %s", e)



def store_chunks(chunks: list[str], doc_id: str, api_key: str | None = None):
    ensure_collection()
    client = get_client()
    vectors = embed_texts(chunks, api_key=api_key)
    points = [
        models.PointStruct(
            id=str(uuid.uuid4()),
            vector=vec,
            payload={"text": chunk, "doc_id": doc_id},
        )
        for chunk, vec in zip(chunks, vectors)
    ]
    client.upsert(settings.qdrant_collection, points)


def delete_by_doc_id(doc_id: str):
    client = get_client()
    client.delete(
        settings.qdrant_collection,
        points_selector=models.FilterSelector(
            filter=models.Filter(must=[
                models.FieldCondition(key="doc_id", match=models.MatchValue(value=doc_id))
            ])
        ),
    )


def search_similar(query: str, top_k: int = 5) -> list[dict]:
    ensure_collection()
    client = get_client()
    vec = embed_query(query)
    results = client.query_points(
        settings.qdrant_collection,
        query=vec,
        limit=top_k,
    )
    return [
        {"text": r.payload["text"], "doc_id": r.payload["doc_id"], "score": r.score}
        for r in results.points
    ]
