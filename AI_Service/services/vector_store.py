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
        client.create_payload_index(name, "doc_id", models.PayloadSchemaType.KEYWORD)
        client.create_payload_index(name, "source_type", models.PayloadSchemaType.KEYWORD)
        client.create_payload_index(name, "library_id", models.PayloadSchemaType.KEYWORD)
    except Exception as e:
        import logging
        logging.warning("ensure_collection 失败: %s", e)


def _analysis_document_filter(doc_id: str | None = None) -> models.Filter:
    must = [
        models.FieldCondition(
            key="source_type",
            match=models.MatchValue(value="analysis_document"),
        )
    ]
    if doc_id:
        must.append(
            models.FieldCondition(
                key="doc_id",
                match=models.MatchValue(value=doc_id),
            )
        )
    return models.Filter(must=must)


def _reference_document_filter(library_ids: list[str]) -> models.Filter:
    return models.Filter(must=[
        models.FieldCondition(
            key="source_type",
            match=models.MatchValue(value="reference_document"),
        ),
        models.FieldCondition(
            key="library_id",
            match=models.MatchAny(any=library_ids),
        ),
    ])


def store_chunks(
    chunks: list[str],
    doc_id: str,
    api_key: str | None = None,
    source_type: str = "analysis_document",
    library_id: str | None = None,
):
    ensure_collection()
    client = get_client()
    vectors = embed_texts(chunks, api_key=api_key)
    points = [
        models.PointStruct(
            id=str(uuid.uuid4()),
            vector=vec,
            payload={
                "text": chunk,
                "doc_id": doc_id,
                "source_type": source_type or "analysis_document",
                "library_id": library_id,
            },
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
    return search_similar_in_document(query, top_k=top_k)


def search_similar_in_document(query: str, top_k: int = 5, doc_id: str | None = None) -> list[dict]:
    ensure_collection()
    client = get_client()
    vec = embed_query(query)
    results = client.query_points(
        settings.qdrant_collection,
        query=vec,
        limit=top_k,
        query_filter=_analysis_document_filter(doc_id),
    )
    return [
        {
            "text": r.payload["text"],
            "doc_id": r.payload["doc_id"],
            "source_type": r.payload.get("source_type"),
            "library_id": r.payload.get("library_id"),
            "score": r.score,
        }
        for r in results.points
    ]


def search_reference_library(query: str, library_ids: list[str], top_k: int = 5) -> list[dict]:
    if not library_ids:
        return []
    ensure_collection()
    client = get_client()
    vec = embed_query(query)
    results = client.query_points(
        settings.qdrant_collection,
        query=vec,
        limit=top_k,
        query_filter=_reference_document_filter(library_ids),
    )
    return [
        {
            "text": r.payload["text"],
            "doc_id": r.payload["doc_id"],
            "source_type": r.payload.get("source_type"),
            "library_id": r.payload.get("library_id"),
            "score": r.score,
        }
        for r in results.points
    ]
