"""Qdrant 向量集合、文档 payload 和相似度检索封装。"""

import uuid

from qdrant_client import QdrantClient, models
from config.settings import settings
from services.embedding import embed_texts, embed_query
from services.model_config import AIModelConfig

_client = None


def get_client() -> QdrantClient:
    """获取进程级共享 Qdrant 客户端。

    Returns:
        使用运行时基础设施配置初始化的 Qdrant 客户端。

    Notes:
        客户端延迟创建，避免仅执行模块导入或健康检查时立即建立外部连接。
    """
    global _client
    if _client is None:
        _client = QdrantClient(
            host=settings.qdrant_host,
            port=settings.qdrant_http_port,
            https=False,
            api_key=settings.qdrant_api_key,
        )
    return _client


def ensure_collection(vector_size: int | None = None):
    """确保 Qdrant 集合存在，并验证实际向量维度兼容。

    Args:
        vector_size: 当前模型产生的向量维度；只读场景可不提供。

    Raises:
        ValueError: 集合不存在且无法确定维度，或现有集合与当前模型不兼容。

    Notes:
        集合维度一旦由模型确定就不能静默切换；发现维度、命名向量或集合类型
        不兼容时必须提示重建，避免写入后产生不可检索的数据。
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
    """构造只匹配分析文档的 Qdrant 过滤器。"""
    return _document_filter("analysis_document", doc_id=doc_id)


def _reference_document_filter(library_ids: list[str]) -> models.Filter:
    """构造只匹配指定参考资料库的 Qdrant 过滤器。"""
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
    """按来源类型及可选文档/资料库 ID 组合 Qdrant 条件。"""
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

    Notes:
        每个片段使用独立 UUID point ID，但通过 payload 中的 `doc_id` 形成逻辑
        文档边界，删除和检索都依赖该字段。
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
    """删除指定逻辑文档对应的全部 Qdrant 向量点。

    Args:
        doc_id: 逻辑文档 ID，可能对应分析文档或参考文档。

    Notes:
        删除按 payload 过滤而不是按 point ID 进行，因此一次调用能清理该文档
        的所有片段。
    """
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
    """分页读取所有匹配点，并在达到上限时立即终止。

    Args:
        scroll_filter: Qdrant payload 过滤条件。
        with_vectors: 是否同时返回原始向量，归档复制时需要开启。
        max_points: 最大允许读取点数，默认使用单文档系统上限。

    Returns:
        所有匹配的 Qdrant point 列表。

    Raises:
        ValueError: 读取点数超过系统上限。
    """
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
    """读取指定来源和文档 ID 的全部片段。

    Args:
        doc_id: 逻辑文档 ID。
        source_type: `analysis_document` 或 `reference_document`。
        with_vectors: 是否返回向量本身。

    Returns:
        按 Qdrant 分页结果合并的 point 列表。
    """
    return scroll_all(
        _document_filter(source_type, doc_id=doc_id),
        with_vectors=with_vectors,
        max_points=settings.ai_max_chunks_per_document,
    )


def clone_analysis_document_to_reference(doc_id: str, library_id: str) -> tuple[str, list[str]]:
    """复制分析文档向量并改写为参考资料 payload。

    Args:
        doc_id: 源分析文档 ID。
        library_id: 目标参考资料库 ID。

    Returns:
        新生成的参考文档 ID，以及从 payload 中提取的文本列表。

    Raises:
        ValueError: 源分析文档没有可归档的向量。

    Notes:
        复制时生成新的逻辑文档 ID 和 point ID，并将 `source_type` 与 `library_id`
        一并改写，保证后续参考资料检索不会命中原分析文档范围。
    """
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
