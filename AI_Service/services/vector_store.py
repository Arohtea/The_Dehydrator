import uuid

from qdrant_client import QdrantClient, models
from config.settings import settings
from services.embedding import embed_texts, embed_query
from services.model_config import AIModelConfig

_client = None


def get_client() -> QdrantClient:
    global _client
    if _client is None:
        _client = QdrantClient(
            host=settings.qdrant_host,
            port=settings.qdrant_http_port,
            api_key=settings.qdrant_api_key,
        )
    return _client


def ensure_collection(vector_size: int | None = None):
    """确保 Qdrant 集合存在，并验证实际向量维度兼容。

    Args:
        vector_size: 当前模型产生的向量维度；只读场景可不提供。

    Raises:
        ValueError: 集合不存在且无法确定维度，或现有集合与当前模型不兼容。
    """
    client = get_client()
    name = settings.qdrant_collection
    try:
        if not client.collection_exists(name):
            if vector_size is None:
                raise ValueError("向量集合不存在，无法确定向量维度")
            client.create_collection(
                collection_name=name,
                vectors_config=models.VectorParams(
                    size=vector_size, distance=models.Distance.COSINE
                ),
            )
        elif vector_size is not None:
            collection = client.get_collection(name)
            vectors_config = collection.config.params.vectors
            if vectors_config is None:
                raise ValueError("现有 Qdrant 集合未配置稠密向量，与当前模型不兼容；请先执行向量重建")
            if isinstance(vectors_config, dict):
                raise ValueError("现有 Qdrant 集合使用命名向量，与当前单向量配置不兼容；请先执行向量重建")
            configured_size = vectors_config.size
            if configured_size != vector_size:
                raise ValueError(
                    f"向量模型输出维度为 {vector_size}，但现有 Qdrant 集合维度为 {configured_size}；"
                    "请使用兼容模型或先执行向量重建"
                )
        client.create_payload_index(name, "doc_id", models.PayloadSchemaType.KEYWORD)
        client.create_payload_index(name, "source_type", models.PayloadSchemaType.KEYWORD)
        client.create_payload_index(name, "library_id", models.PayloadSchemaType.KEYWORD)
    except ValueError:
        raise
    except Exception as e:
        import logging
        logging.warning("ensure_collection 失败: %s", e)


def _analysis_document_filter(doc_id: str | None = None) -> models.Filter:
    return _document_filter("analysis_document", doc_id=doc_id)


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


def _document_filter(source_type: str, doc_id: str | None = None, library_id: str | None = None) -> models.Filter:
    must = [
        models.FieldCondition(
            key="source_type",
            match=models.MatchValue(value=source_type),
        )
    ]
    if doc_id:
        must.append(
            models.FieldCondition(
                key="doc_id",
                match=models.MatchValue(value=doc_id),
            )
        )
    if library_id:
        must.append(
            models.FieldCondition(
                key="library_id",
                match=models.MatchValue(value=library_id),
            )
        )
    return models.Filter(must=must)


def store_chunks(
    chunks: list[str],
    doc_id: str,
    vector_config: AIModelConfig,
    source_type: str = "analysis_document",
    library_id: str | None = None,
):
    """使用显式向量配置写入文档片段。

    Args:
        chunks: 待写入的文档片段。
        doc_id: 文档 ID。
        vector_config: 本次写入使用的向量模型配置。
        source_type: 文档来源类型。
        library_id: 参考资料集 ID，分析文档可为空。

    Raises:
        ValueError: 模型输出维度与现有集合不兼容。
    """
    vectors = embed_texts(chunks, config=vector_config)
    ensure_collection(len(vectors[0]) if vectors else None)
    client = get_client()
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


def scroll_all(
    scroll_filter: models.Filter,
    with_vectors: bool = False,
    max_points: int | None = None,
):
    """分页读取所有匹配点，并在达到上限时立即终止。"""
    ensure_collection()
    client = get_client()
    points = []
    point_limit = max_points or settings.ai_max_chunks_per_document
    offset = None
    while True:
        page, offset = client.scroll(
            collection_name=settings.qdrant_collection,
            scroll_filter=scroll_filter,
            limit=settings.ai_qdrant_scroll_page_size,
            offset=offset,
            with_payload=True,
            with_vectors=with_vectors,
        )
        points.extend(page)
        if len(points) > point_limit:
            raise ValueError("向量片段数量超过系统上限")
        if offset is None:
            return points


def get_document_points(
    doc_id: str,
    source_type: str = "analysis_document",
    with_vectors: bool = False,
):
    return scroll_all(
        _document_filter(source_type, doc_id=doc_id),
        with_vectors=with_vectors,
        max_points=settings.ai_max_chunks_per_document,
    )


def clone_analysis_document_to_reference(doc_id: str, library_id: str) -> tuple[str, list[str]]:
    ensure_collection()
    client = get_client()
    points = get_document_points(doc_id, source_type="analysis_document", with_vectors=True)
    if not points:
        raise ValueError("未找到可归档的分析文档向量")

    new_doc_id = str(uuid.uuid4())
    cloned_points = []
    texts = []
    for point in points:
        payload = dict(point.payload or {})
        texts.append(payload.get("text", ""))
        payload["doc_id"] = new_doc_id
        payload["source_type"] = "reference_document"
        payload["library_id"] = library_id
        cloned_points.append(models.PointStruct(
            id=str(uuid.uuid4()),
            vector=point.vector,
            payload=payload,
        ))

    client.upsert(settings.qdrant_collection, cloned_points)
    return new_doc_id, texts


def search_similar(query: str, vector_config: AIModelConfig, top_k: int = 5) -> list[dict]:
    """使用显式向量配置检索相似分析文档片段。

    Args:
        query: 查询文本。
        vector_config: 本次检索使用的向量模型配置。
        top_k: 最大返回数量。

    Returns:
        按相似度排序的文档片段。
    """
    return search_similar_in_document(query, vector_config=vector_config, top_k=top_k)


def search_similar_in_document(query: str, vector_config: AIModelConfig,
                                top_k: int = 5, doc_id: str | None = None) -> list[dict]:
    """在分析文档范围内执行向量检索。

    Args:
        query: 查询文本。
        vector_config: 本次检索使用的向量模型配置。
        top_k: 最大返回数量。
        doc_id: 可选的限定文档 ID。

    Returns:
        按相似度排序的分析文档片段。

    Raises:
        ValueError: 模型输出维度与现有集合不兼容。
    """
    vec = embed_query(query, config=vector_config)
    ensure_collection(len(vec))
    client = get_client()
    bounded_top_k = min(max(top_k, 1), settings.ai_max_search_results)
    results = client.query_points(
        settings.qdrant_collection,
        query=vec,
        limit=bounded_top_k,
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


def search_reference_library(query: str, library_ids: list[str], vector_config: AIModelConfig,
                             top_k: int = 5) -> list[dict]:
    """在指定参考资料集中执行向量检索。

    Args:
        query: 查询文本。
        library_ids: 参考资料集 ID。
        vector_config: 本次检索使用的向量模型配置。
        top_k: 最大返回数量。

    Returns:
        按相似度排序的参考资料片段。

    Raises:
        ValueError: 模型输出维度与现有集合不兼容。
    """
    if not library_ids:
        return []
    vec = embed_query(query, config=vector_config)
    ensure_collection(len(vec))
    client = get_client()
    bounded_top_k = min(max(top_k, 1), settings.ai_max_search_results)
    results = client.query_points(
        settings.qdrant_collection,
        query=vec,
        limit=bounded_top_k,
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
